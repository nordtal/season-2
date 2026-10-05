package eu.nordtal.season.dev;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StackTest {

    @Test
    void thePackIsStoredAsTwoValuesOfTheProxysPackGroup() {
        final String sql = Stack.storePack("http://localhost:8081/p.zip", "abc");

        assertTrue(sql.contains("('proxy', 'pack', 'url', to_jsonb('http://localhost:8081/p.zip'::text)"), sql);
        assertTrue(sql.contains("('proxy', 'pack', 'sha1', to_jsonb('abc'::text)"), sql);
        assertTrue(sql.contains("ON CONFLICT (service, name, path) DO UPDATE"), sql);
    }

    @Test
    void aQuoteInTheUrlStaysInsideItsLiteral() {
        final String sql = Stack.storePack("http://localhost/it's.zip", "abc");

        assertTrue(sql.contains("to_jsonb('http://localhost/it''s.zip'::text)"), sql);
    }
}
