package com.albsd.edgecase;

import java.io.IOException;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.text.ParseException;

/**
 * Edge case: multi-catch exceptions.
 * Hypothesis: j2k splits multi-catch into separate catch blocks instead of
 * using a single catch with a union type or a common supertype.
 * Expected idiomatic Kotlin: catch (e: IOException) still single block,
 * or catch (e: Exception) when types share a parent.
 */
public class MultiCatch {

    // Basic two-type multi-catch
    public String readResource(String path) {
        try {
            return doRead(path);
        } catch (IOException | URISyntaxException e) {
            return "error: " + e.getMessage();
        }
    }

    // Three types in one catch
    public void processAll(String input) {
        try {
            doAll(input);
        } catch (IOException | SQLException | ParseException e) {
            System.err.println("Failed: " + e.getClass().getSimpleName() + " — " + e.getMessage());
        } finally {
            System.out.println("done");
        }
    }

    // Multi-catch where one type is a subtype of another (edge within edge)
    public void riskyParse(String s) {
        try {
            Integer.parseInt(s);
        } catch (NumberFormatException | IllegalArgumentException e) {
            // NumberFormatException extends IllegalArgumentException —
            // compiler normally warns; j2k should handle gracefully
            System.err.println(e.getMessage());
        }
    }

    private String doRead(String p) throws IOException, URISyntaxException { return p; }
    private void doAll(String s) throws IOException, SQLException, ParseException {}
}
