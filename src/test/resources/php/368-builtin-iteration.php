<?php

function show($label, $value) {
    echo "== ", $label, " ==\n";
    print_r($value);
    echo "\n";
}

// Packed arrays.
$packed = [3, 1, 2];
show("merge packed", array_merge($packed, [4, 5]));
show("reverse packed", array_reverse($packed));
show("reverse preserve", array_reverse([4 => "a", 9 => "b", "x" => "c"], true));
show("slice packed", array_slice($packed, 1));
show("slice negative", array_slice($packed, -2, 1));
show("slice preserve", array_slice([4 => "a", 9 => "b", "x" => "c"], 1, 2, true));
show("chunk packed", array_chunk($packed, 2));
show("chunk preserve", array_chunk([4 => "a", 9 => "b", "x" => "c"], 2, true));
show("flip", array_flip(["a" => 10, "b" => 20, "c" => 10]));
show("change case", array_change_key_case(["FirSt" => 1, "SecOnd" => 2, 7 => 3], CASE_UPPER));
show("change case collision", array_change_key_case(["FirSt" => 1, "first" => 2, "FIRST" => 3], CASE_LOWER));
show("replace", array_replace([4 => "a", "x" => 1], [4 => "b", "y" => 2]));
show("merge recursive", array_merge_recursive(["a" => 1, "b" => [2]], ["a" => 3, "b" => [4, 5]]));
show("replace recursive", array_replace_recursive(["a" => ["x" => 1], "b" => 2], ["a" => ["y" => 3], "b" => 4]));
show("diff", array_diff(["a" => "green", "b" => "brown", "c" => "blue", 0 => "red"], ["green", "blue"]));
show("intersect", array_intersect(["a" => "green", "b" => "brown", "c" => "blue"], ["green", "blue"]));
show("diff key", array_diff_key(["a" => 1, "b" => 2, 4 => 3], ["a" => 0, 4 => 0]));
show("map null", array_map(null, [1, 2, 3]));
show("filter", array_filter([0, 1, "", "x", null, [], [0]]));
show("unique", array_unique(["a" => "green", "b" => "green", "c" => "blue"]));
show("union", ["a" => 1, 2 => "x"] + ["a" => 9, "b" => 2, 2 => "y"]);

// Hash array with holes from unset.
$holes = [0 => "a", 1 => "b", 2 => "c", 3 => "d", 7 => "e"];
unset($holes[1], $holes[2]);
show("holes", $holes);
show("holes merge", array_merge($holes, [5 => "x", "k" => "y"]));
show("holes reverse", array_reverse($holes));
show("holes slice", array_slice($holes, 1, 2));
show("holes chunk", array_chunk($holes, 2));
$u = $holes;
array_unshift($u, "top", 0);
show("unshift holes", $u);
echo "implode holes: ", implode("|", $holes), "\n";

// References in the array are unwrapped by the direct loops.
$refs = ["a" => 1, "b" => 2];
$refs["c"] = &$refs["a"];
$refs["a"] = 10;
show("refs", $refs);
show("refs merge", array_merge($refs, ["d" => 4]));
show("refs reverse", array_reverse($refs));
show("refs slice", array_slice($refs, 0, 2));
show("refs flip", array_flip($refs));
show("refs replace", array_replace($refs, ["b" => 20]));
echo "refs implode: ", implode(",", $refs), "\n";

// Nested arrays.
$nested = ["a" => ["x" => 1], "b" => [2, 3], "c" => []];
show("nested merge", array_merge($nested, ["d" => [4]]));
show("nested reverse", array_reverse($nested));
show("nested slice", array_slice($nested, 1, 2, true));
show("nested replace recursive", array_replace_recursive($nested, ["a" => ["y" => 2], "c" => [5]]));
show("nested serialize", serialize($nested));
show("nested cast", (object) $nested);
show("nested same", $nested === $nested);
show("nested different", ["a" => 1] === ["a" => 2]);

show("strtr", strtr("Hi all, I said hello", ["Hi" => "Hello", "hello" => "hi", "all" => "everyone"]));

// implode() converts objects, and __toString() changes the array being imploded.
class Mutator {
    public $label;
    public function __construct($label) {
        $this->label = $label;
    }
    public function __toString(): string {
        global $implodeArray;
        $implodeArray[] = "added-" . $this->label;
        unset($implodeArray[0]);
        return $this->label;
    }
}
$implodeArray = [new Mutator("one"), "two", "three"];
echo "implode objects: ", implode("|", $implodeArray), "\n";
show("after implode", $implodeArray);
