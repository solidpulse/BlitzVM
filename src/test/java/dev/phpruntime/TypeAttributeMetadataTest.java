package dev.phpruntime;

import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.PhpMainRootNode;
import dev.phpruntime.nodes.expr.ClosureNode;
import dev.phpruntime.parser.Parser;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpAttribute;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpFunction;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpParameter;
import dev.phpruntime.runtime.PhpSignature;
import dev.phpruntime.runtime.PhpType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the type and attribute metadata recorded by the parser for reflection. */
class TypeAttributeMetadataTest {

    private static final String SCRIPT = """
            <?php
            namespace App\\Model;

            use App\\Contracts\\HasName as Named;
            use App\\Attributes\\Route;

            #[Route('/thing', methods: ['GET', 'POST'])]
            #[Marker,]
            function handle(
                #[Param] ?int $a,
                #[Param('b', flag: true)] Named|string|int|null $b = 'd',
                ?callable $cb = null,
                int &$ref,
                string ...$rest
            ): ?string { return null; }

            #[Marker]
            trait T {
                #[Marker]
                public ?int $count = null;

                #[Marker]
                public const int LIMIT = 10;

                #[Marker]
                public function m(#[Param] (Named&\\Countable)|null $x): static { return $this; }

                #[Marker]
                public function both(#[Param] Named&\\Countable $x): void {}
            }

            #[Marker]
            interface I {
                #[Marker]
                public function f(#[Param] int $x): void;
            }

            #[Marker]
            class Base {}

            #[Marker]
            class C extends Base {
                use T;

                #[Marker]
                public function __construct(
                    #[Marker] public readonly int $id,
                    #[Marker] protected ?string $name = null,
                ) {}

                #[Marker]
                public function f(#[Param] int $x): void {}

                #[Marker]
                public function selfType(#[Param] self $x, #[Param] parent $y): self {
                    return $this;
                }
            }

            #[Marker]
            enum Suit: string {
                #[Marker('heart')]
                case Hearts = 'H';

                #[Marker]
                case Spades = 'S';
            }

            $closure = #[Marker] function (#[Param] int $n): ?int { return $n; };
            $arrow = #[Marker] fn(#[Param] string $s): string => $s;
            """;

    private static final String NS = "App\\Model\\";

    private static org.graalvm.polyglot.Engine engine;
    private static PhpLanguage language;

    private static PhpLanguage language() {
        if (language != null) {
            return language;
        }
        // Parsing builds Truffle roots, so the language instance must be the one the
        // polyglot engine created and initialised rather than a bare constructor call.
        engine = org.graalvm.polyglot.Engine.newBuilder("php")
                .allowExperimentalOptions(true)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        try (org.graalvm.polyglot.Context context = org.graalvm.polyglot.Context.newBuilder("php")
                .engine(engine).build()) {
            context.eval(org.graalvm.polyglot.Source.newBuilder("php", "<?php\n", "warm.php").buildLiteral());
            Object contextImpl = field(context, "receiver");
            for (Object languageContext : (Object[]) field(contextImpl, "contexts")) {
                if (!String.valueOf(field(languageContext, "language")).contains("id=php,")) {
                    continue;
                }
                var getInstance = languageContext.getClass().getDeclaredMethod("getLanguageInstance");
                getInstance.setAccessible(true);
                Object instance = getInstance.invoke(languageContext);
                language = (PhpLanguage) field(instance, "spi");
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        if (language == null) {
            throw new AssertionError("php language instance not found");
        }
        return language;
    }

    private static Object field(Object receiver, String name) throws ReflectiveOperationException {
        for (Class<?> type = receiver.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(receiver);
            } catch (NoSuchFieldException ignored) {
                // Try the superclass.
            }
        }
        throw new NoSuchFieldException(name);
    }

    @AfterAll
    static void closeEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void reflectionTypeStringsFollowPhp() {
        assertEquals("?int", PhpType.union(List.of(named("int"), named("null"))).toString());
        assertEquals("string|int|null", PhpType.union(List.of(named("int"), named("string"), named("null"))).toString());
        assertEquals("?iterable", PhpType.nullable(named("iterable")).toString());
        assertEquals("Traversable|array|null", PhpType.union(List.of(named("iterable"), named("null"))).toString());
        assertEquals("A&B", PhpType.intersection(List.of(named("A"), named("B"))).toString());
        assertEquals("(A&B)|null",
                PhpType.union(List.of(PhpType.intersection(List.of(named("A"), named("B"))), named("null")))
                        .toString());
        assertEquals("A|B|int", PhpType.union(List.of(named("A"), named("B"), named("int"))).toString());
        assertEquals("B|A|int", PhpType.union(List.of(named("int"), named("B"), named("A"))).toString());
    }

    @Test
    void recordsFunctionTypesAndAttributes() {
        PhpMainRootNode main = parse();
        PhpFunctionRootNode handle = functionRoot(main, NS + "handle");

        List<PhpAttribute> attributes = handle.attributes();
        assertEquals(List.of("App\\Attributes\\Route", NS + "Marker"), names(attributes));
        PhpAttribute route = attributes.get(0);
        assertEquals(2, route.arguments().size());
        assertNull(route.arguments().get(0).name());
        assertEquals("/thing", route.arguments().get(0).value());
        assertEquals("methods", route.arguments().get(1).name());
        assertTrue(route.arguments().get(1).value() instanceof PhpArray array
                && array.size() == 2);

        List<PhpParameter> parameters = handle.parameters();
        assertEquals(5, parameters.size());
        assertEquals("?int", parameters.get(0).type().toString());
        assertEquals(NS + "Param", parameters.get(0).attributes().get(0).name());
        assertFalse(parameters.get(0).hasDefault());

        assertEquals("App\\Contracts\\HasName|string|int|null", parameters.get(1).type().toString());
        assertTrue(parameters.get(1).hasDefault());
        assertEquals("d", parameters.get(1).defaultValue());
        assertNull(parameters.get(1).defaultTarget());

        assertEquals("?callable", parameters.get(2).type().toString());
        assertTrue(parameters.get(2).hasDefault());
        assertSame(PhpNull.INSTANCE, parameters.get(2).defaultValue());

        assertEquals("int", parameters.get(3).type().toString());
        assertTrue(parameters.get(3).byRef());
        assertFalse(parameters.get(3).variadic());
        assertTrue(parameters.get(4).variadic());
        assertEquals("string", parameters.get(4).type().toString());
        assertEquals("?string", handle.returnType().toString());
    }

    @Test
    void recordsTraitInterfaceAndClassMembers() {
        PhpMainRootNode main = parse();
        PhpClassTemplate trait = template(main, NS + "T");
        assertEquals(List.of(NS + "Marker"), names(trait.attributes()));
        PhpClassTemplate.PropertyDecl count = property(trait, "count");
        assertEquals("?int", count.type().toString());
        assertEquals(List.of(NS + "Marker"), names(count.attributes()));
        PhpClassTemplate.ConstantDecl limit = constant(trait, "LIMIT");
        assertEquals("int", limit.type().toString());
        assertEquals(List.of(NS + "Marker"), names(limit.attributes()));
        PhpClassTemplate.MethodDecl m = trait.methods().get("m");
        assertEquals("static", m.signature().returnType().toString());
        assertEquals("(App\\Contracts\\HasName&Countable)|null",
                m.signature().parameters().get(0).type().toString());
        assertEquals(List.of(NS + "Marker"), names(m.attributes()));
        PhpClassTemplate.MethodDecl both = trait.methods().get("both");
        assertEquals("App\\Contracts\\HasName&Countable",
                both.signature().parameters().get(0).type().toString());

        PhpClassTemplate iface = template(main, NS + "I");
        PhpClassTemplate.MethodDecl f = iface.methods().get("f");
        assertNull(f.callTarget());
        assertEquals("void", f.signature().returnType().toString());
        assertEquals("int", f.signature().parameters().get(0).type().toString());

        PhpClassTemplate cls = template(main, NS + "C");
        assertEquals(List.of(NS + "Marker"), names(cls.attributes()));
        PhpClassTemplate.PropertyDecl id = property(cls, "id");
        assertEquals("int", id.type().toString());
        assertEquals(List.of(NS + "Marker"), names(id.attributes()));
        PhpClassTemplate.PropertyDecl name = property(cls, "name");
        assertEquals("?string", name.type().toString());
        PhpParameter promotedId = cls.methods().get("__construct").signature().parameters().get(0);
        assertTrue(promotedId.promoted());
        assertEquals("int", promotedId.type().toString());
        assertEquals(List.of(NS + "Marker"), names(promotedId.attributes()));

        PhpClassTemplate.MethodDecl selfType = cls.methods().get("selftype");
        assertEquals(NS + "C", selfType.signature().parameters().get(0).type().toString());
        assertEquals(NS + "Base", selfType.signature().parameters().get(1).type().toString());
        assertEquals(NS + "C", selfType.signature().returnType().toString());
    }

    @Test
    void recordsEnumBackingTypeAndCases() {
        PhpMainRootNode main = parse();
        PhpClassTemplate suit = template(main, NS + "Suit");
        assertTrue(suit.isEnum());
        assertFalse(suit.isTrait());
        assertEquals("string", suit.enumBackingType().toString());
        assertEquals(List.of(NS + "Marker"), names(suit.attributes()));
        assertEquals(List.of("Hearts", "Spades"), suit.enumCases().stream()
                .map(PhpClassTemplate.EnumCaseDecl::name).toList());
        PhpClassTemplate.EnumCaseDecl hearts = suit.enumCase("Hearts");
        assertEquals("H", hearts.constValue());
        assertEquals(List.of(NS + "Marker"), names(hearts.attributes()));
        assertEquals("heart", hearts.attributes().get(0).arguments().get(0).value());
    }

    @Test
    void recordsClosureAndArrowFunctionTypesAndAttributes() {
        PhpMainRootNode main = parse();
        List<ClosureNode> closures = new ArrayList<>();
        collectClosures(main, closures);
        assertEquals(2, closures.size());

        PhpSignature closure = signature(closures.get(0));
        assertEquals(List.of(NS + "Marker"), names(closure.attributes()));
        assertEquals("?int", closure.returnType().toString());
        assertEquals("int", closure.parameters().get(0).type().toString());
        assertEquals(List.of(NS + "Param"), names(closure.parameters().get(0).attributes()));

        PhpSignature arrow = signature(closures.get(1));
        assertEquals("string", arrow.returnType().toString());
        assertEquals("string", arrow.parameters().get(0).type().toString());
    }

    private static PhpType named(String name) {
        return new PhpType.Named(name);
    }

    private static List<String> names(List<PhpAttribute> attributes) {
        return attributes.stream().map(PhpAttribute::name).toList();
    }

    private static PhpMainRootNode parse() {
        Source source = Source.newBuilder("php", SCRIPT, "metadata.php").build();
        return new Parser(language(), source).parseMain();
    }

    private static PhpClassTemplate template(PhpMainRootNode main, String name) {
        for (PhpClassTemplate template : main.topLevelClasses()) {
            if (template.name().equals(name)) {
                return template;
            }
        }
        throw new AssertionError("missing class " + name);
    }

    private static PhpClassTemplate.PropertyDecl property(PhpClassTemplate template, String name) {
        for (PhpClassTemplate.PropertyDecl property : template.properties()) {
            if (property.name().equals(name)) {
                return property;
            }
        }
        throw new AssertionError("missing property " + name);
    }

    private static PhpClassTemplate.ConstantDecl constant(PhpClassTemplate template, String name) {
        for (PhpClassTemplate.ConstantDecl constant : template.constants()) {
            if (constant.name().equals(name)) {
                return constant;
            }
        }
        throw new AssertionError("missing constant " + name);
    }

    private static PhpFunctionRootNode functionRoot(PhpMainRootNode main, String name) {
        for (PhpFunction function : main.functions()) {
            if (function.name().equals(name)) {
                PhpFunctionRootNode root = PhpFunctionRootNode.of(function.callTarget().getRootNode());
                assertNotNull(root, "function root for " + name);
                return root;
            }
        }
        throw new AssertionError("missing function " + name);
    }

    private static PhpSignature signature(ClosureNode closure) {
        PhpFunctionRootNode root = PhpFunctionRootNode.of(closure.callTarget().getRootNode());
        assertNotNull(root, "closure root");
        return root.signature();
    }

    private static void collectClosures(Node node, List<ClosureNode> out) {
        if (node instanceof ClosureNode closure) {
            out.add(closure);
        }
        for (Node child : node.getChildren()) {
            collectClosures(child, out);
        }
    }
}
