package cz.loplex.lucenemcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FindReferencesToolTest {

    @Test
    fun `finds real usages of a Kotlin class, classified as definition, call and type`(@TempDir tempDir: File) {
        File(tempDir, "App.kt").writeText(
            """
            class UserService {
                fun login() {}
            }
            class Client(val service: UserService) {
                fun run() {
                    val service = UserService()
                    service.login()
                }
            }
            """.trimIndent()
        )

        val result = runFindReferences(tempDir, "UserService", 50)
        assertTrue(result.contains("App.kt:1 [definition]"))
        assertTrue(result.contains("App.kt:4 [type]")) // constructor parameter type
        assertTrue(result.contains("App.kt:6 [call]")) // UserService() constructor call
    }

    @Test
    fun `a symbol only mentioned in a comment or string literal is not a reference`(@TempDir tempDir: File) {
        File(tempDir, "Sample.kt").writeText(
            """
            package test

            class RealTarget {
                fun useIt() {
                    val x = "RealTarget is just a string here, not a reference"
                    println(x)
                }
            }
            """.trimIndent()
        )

        val result = runFindReferences(tempDir, "RealTarget", 50)
        // Only the declaration itself is a real occurrence; the string mention must not count.
        assertTrue(result.contains("Found 1 reference"))
        assertTrue(result.contains("[definition]"))
    }

    @Test
    fun `finds a Java method call site`(@TempDir tempDir: File) {
        File(tempDir, "Greeter.java").writeText(
            """
            public class Greeter {
                public String greet() {
                    return "hi";
                }
                public void run() {
                    greet();
                }
            }
            """.trimIndent()
        )

        val result = runFindReferences(tempDir, "greet", 50)
        assertTrue(result.contains("Greeter.java:2 [definition]"))
        assertTrue(result.contains("Greeter.java:6 [call]"))
    }

    @Test
    fun `blank symbol is a clean error`(@TempDir tempDir: File) {
        val result = runFindReferences(tempDir, "  ", 50)
        assertTrue(result.contains("Missing required argument"))
    }

    @Test
    fun `non-identifier symbol is rejected with a clean error`(@TempDir tempDir: File) {
        val result = runFindReferences(tempDir, "not an identifier!", 50)
        assertTrue(result.contains("Invalid symbol"))
    }

    @Test
    fun `no matches gives a clean message`(@TempDir tempDir: File) {
        File(tempDir, "App.kt").writeText("fun main() {}\n")

        val result = runFindReferences(tempDir, "NeverMentioned", 50)
        assertTrue(result.contains("No references found"))
        assertFalse(result.contains("Exception"))
    }

    @Test
    fun `bare reference in an unrelated, unimported, different-package file is filtered out`(@TempDir tempDir: File) {
        File(tempDir, "Def.kt").writeText(
            """
            package pkg.a

            fun helper() {}
            """.trimIndent()
        )
        File(tempDir, "Unrelated.kt").writeText(
            """
            package pkg.b

            fun other(helper: Int) {
                println(helper)
            }
            """.trimIndent()
        )

        val result = runFindReferences(tempDir, "helper", 50)
        assertTrue(result.contains("Def.kt:3 [definition]"))
        assertFalse(result.contains("Unrelated.kt"))
    }

    @Test
    fun `bare reference is kept when the file imports the symbol by name`(@TempDir tempDir: File) {
        File(tempDir, "Def.kt").writeText(
            """
            package pkg.a

            fun helper() {}
            """.trimIndent()
        )
        File(tempDir, "Caller.kt").writeText(
            """
            package pkg.c

            import pkg.a.helper

            fun run() {
                helper()
            }
            """.trimIndent()
        )

        val result = runFindReferences(tempDir, "helper", 50)
        assertTrue(result.contains("Caller.kt:6 [call]"))
    }

    @Test
    fun `bare reference is kept when the file shares the defining package, no import needed`(@TempDir tempDir: File) {
        File(tempDir, "Def.kt").writeText(
            """
            package pkg.a

            fun helper() {}
            """.trimIndent()
        )
        File(tempDir, "SamePackage.kt").writeText(
            """
            package pkg.a

            fun run() {
                helper()
            }
            """.trimIndent()
        )

        val result = runFindReferences(tempDir, "helper", 50)
        assertTrue(result.contains("SamePackage.kt:4 [call]"))
    }

    @Test
    fun `qualified member-style access is never filtered out, even in an unrelated package`(@TempDir tempDir: File) {
        File(tempDir, "Def.kt").writeText(
            """
            package pkg.a

            fun helper() {}
            """.trimIndent()
        )
        File(tempDir, "Unrelated.kt").writeText(
            """
            package pkg.b

            fun run(receiver: Any) {
                receiver.helper()
            }
            """.trimIndent()
        )

        val result = runFindReferences(tempDir, "helper", 50)
        assertTrue(result.contains("Unrelated.kt:4"))
    }

    @Test
    fun `without any repo definition, filtering is disabled so external symbols still surface`(@TempDir tempDir: File) {
        File(tempDir, "A.kt").writeText(
            """
            package pkg.a

            fun run() {
                println("hi")
            }
            """.trimIndent()
        )

        val result = runFindReferences(tempDir, "println", 50)
        assertTrue(result.contains("A.kt") && result.contains("[call]"))
    }

    @Test
    fun `maxMatches caps the number of returned references`(@TempDir tempDir: File) {
        val sb = StringBuilder("fun helper() {}\n")
        repeat(5) { sb.append("val x = helper()\n") }
        File(tempDir, "many.kt").writeText(sb.toString())

        val matches = findReferences(tempDir, "helper", maxMatches = 2)
        assertTrue(matches.size <= 2)
    }

    @Test
    fun `a shared AstCache still reflects file edits across calls`(@TempDir tempDir: File) {
        val file = File(tempDir, "App.kt")
        file.writeText("fun helper() {}\nval x = helper()\n")
        val cache = AstCache()

        val before = runFindReferences(tempDir, "helper", 50, cache)
        assertTrue(before.contains("App.kt:2"))
        assertFalse(before.contains("App.kt:3"))

        Thread.sleep(1100) // ensure a distinct filesystem mtime (1s resolution on some filesystems)
        file.writeText("fun helper() {}\nval x = helper()\nval y = helper()\n")
        val after = runFindReferences(tempDir, "helper", 50, cache)

        assertTrue(after.contains("App.kt:2"))
        assertTrue(after.contains("App.kt:3"))
    }

    @Test
    fun `TS import path resolution drops a bare hit whose import resolves elsewhere, not just to any file mentioning the name`(@TempDir tempDir: File) {
        File(tempDir, "real.ts").writeText("export class Config {}\n")
        File(tempDir, "other.ts").writeText("export class Unrelated {}\n")
        // Consumer's import statement mentions "Config" by name but its module path resolves to
        // other.ts, a real project file that does NOT define Config — the old name-only heuristic
        // would have kept this file as a candidate purely because the import statement's text
        // contains "Config".
        File(tempDir, "consumer.ts").writeText(
            """
            import { Config } from './other';
            function run() { new Config(); }
            """.trimIndent()
        )

        val result = runFindReferences(tempDir, "Config", 50)
        assertTrue(result.contains("real.ts:1 [definition]"))
        assertFalse(result.contains("consumer.ts"))
    }

    @Test
    fun `TS import path resolution keeps a bare hit whose import resolves to the real defining file`(@TempDir tempDir: File) {
        File(tempDir, "real.ts").writeText("export class Config {}\n")
        File(tempDir, "consumer.ts").writeText(
            """
            import { Config } from './real';
            function run() { new Config(); }
            """.trimIndent()
        )

        val result = runFindReferences(tempDir, "Config", 50)
        assertTrue(result.contains("consumer.ts:2"))
    }

    @Test
    fun `TS bare package specifier is unresolvable and falls back to the permissive name-mention check`(@TempDir tempDir: File) {
        File(tempDir, "real.ts").writeText("export class Config {}\n")
        File(tempDir, "consumer.ts").writeText(
            """
            import { Config } from 'some-external-lib';
            function run() { new Config(); }
            """.trimIndent()
        )

        val result = runFindReferences(tempDir, "Config", 50)
        assertTrue(result.contains("consumer.ts:2"))
    }

    @Test
    fun `Python import path resolution drops a bare hit whose relative import resolves elsewhere`(@TempDir tempDir: File) {
        File(tempDir, "real.py").writeText("class Config:\n    pass\n")
        File(tempDir, "other.py").writeText("class Unrelated:\n    pass\n")
        File(tempDir, "consumer.py").writeText(
            """
            from .other import Config
            Config()
            """.trimIndent()
        )

        val result = runFindReferences(tempDir, "Config", 50)
        assertTrue(result.contains("real.py:1 [definition]"))
        assertFalse(result.contains("consumer.py"))
    }

    @Test
    fun `Python import path resolution keeps a bare hit whose absolute import resolves to the real defining file`(@TempDir tempDir: File) {
        val pkgDir = File(tempDir, "pkg").apply { mkdirs() }
        File(pkgDir, "mod.py").writeText("class Config:\n    pass\n")
        File(tempDir, "consumer.py").writeText(
            """
            from pkg.mod import Config
            Config()
            """.trimIndent()
        )

        val result = runFindReferences(tempDir, "Config", 50)
        assertTrue(result.contains("consumer.py:2"))
    }
}
