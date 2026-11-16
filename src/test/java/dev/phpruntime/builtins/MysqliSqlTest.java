package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpStrings;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class MysqliSqlTest {
    @Test
    void unicodeSqlDecodesExactlyOnce() throws Exception {
        String sql = "INSERT INTO `clé` VALUES ('é😀')";
        assertEquals(sql, MysqliSql.toJava(PhpStrings.fromJava(sql)));
    }

    @Test
    void binaryLiteralUnescapesBeforeHexEncoding() throws Exception {
        String bytes = "\0\n\r\t\b\u001a'\"\\\u00ff\u0080";
        String sql = "INSERT INTO t VALUES ('" + MysqliFunctions.escape(bytes) + "', '" + PhpStrings.fromJava("é😀") + "')";
        assertEquals("INSERT INTO t VALUES (X'000a0d09081a27225cff80', 'é😀')", MysqliSql.toJava(sql));
    }

    @Test
    void commentsAndQuotedIdentifiersAreNotLiterals() throws Exception {
        String sql = "SELECT `a'b`, 'it''s\u00ff', \"x\u00ff\" /* 'not a literal' */ -- 'still a comment'\n# 'comment'";
        assertEquals("SELECT `a'b`, X'69742773ff', X'78ff' /* 'not a literal' */ -- 'still a comment'\n# 'comment'", MysqliSql.toJava(sql));
        assertThrows(SQLException.class, () -> MysqliSql.toJava("SELECT `\u00ff`, 'ok'"));
    }

    @Test
    void sqlMetacharactersStayInsideTheHexLiteral() throws Exception {
        String value = "\u00ff'); DROP TABLE t; --";
        String sql = "SELECT '" + MysqliFunctions.escape(value) + "'";
        assertEquals("SELECT X'ff27293b2044524f50205441424c4520743b202d2d'", MysqliSql.toJava(sql));
    }

    @Test
    void binaryCharsetIntroducersDoNotCorruptTheHexSyntax() throws Exception {
        assertEquals("SELECT X'ff', X'80', 'é'", MysqliSql.toJava("SELECT _binary'\u00ff', _latin1 '\u0080', '"
                + PhpStrings.fromJava("é") + "'"));
    }
}
