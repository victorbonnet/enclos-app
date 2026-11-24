package io.github.victorbonnet.enclos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure search and filter rules for the Model page (no Compose). */
class ModelSearchTest {
    private val providers = listOf(
        ModelProvider("anthropic", "Anthropic", usable = true, setup = null),
        ModelProvider("openai", "OpenAI", usable = true, setup = null),
        ModelProvider("openrouter", "OpenRouter", usable = true, setup = null),
        ModelProvider("google", "Google Gemini", usable = true, setup = null),
        ModelProvider("local-ollama", "Ollama", usable = true, setup = null),
        ModelProvider("xai", "xAI", usable = false, setup = "Set XAI_API_KEY on the host."),
        ModelProvider("mistral", "Mistral", usable = false, setup = "Run enclos auth login mistral on the host."),
        ModelProvider("bedrock", "AWS Bedrock", usable = false, setup = "Configure AWS credentials on the host."),
    )

    @Test
    fun words_match_is_case_and_accent_insensitive_in_any_order() {
        assertTrue(wordsMatch("Anthropic", ""))
        assertTrue(wordsMatch("Anthropic", "   "))
        assertTrue(wordsMatch("Anthropic", "ANTHROPIC"))
        assertTrue(wordsMatch("Café Modèle", "cafe modele"))
        assertTrue(wordsMatch("Cafe Modele", "café"))
        assertTrue(wordsMatch("Claude 3.7 Sonnet", "sonnet claude"))
        assertTrue(wordsMatch("Claude 3.7 Sonnet", "3.7   CLAUDE"))
        assertFalse(wordsMatch("Claude 3.7 Sonnet", "sonnet opus"))
        assertTrue(wordsMatch("DeepSeek R1 deepseek/deepseek-r1:free", ":free"))
    }

    @Test
    fun provider_matches_label_and_id_never_setup_and_respects_filter() {
        val xai = providers.first { it.id == "xai" }
        val ollama = providers.first { it.id == "local-ollama" }
        assertTrue(providerMatches(ollama, "ollama", null))
        assertTrue(providerMatches(ollama, "local", null))
        assertTrue(providerMatches(ollama, "ollama local", null))
        assertFalse("setup sentence is never searched", providerMatches(xai, "XAI_API_KEY", null))
        assertFalse("setup sentence is never searched", providerMatches(xai, "host", null))

        assertTrue(providerMatches(ollama, "", ModelProviderFilter.ReadyHere))
        assertFalse(providerMatches(ollama, "", ModelProviderFilter.NeedsHostSetup))
        assertFalse(providerMatches(xai, "", ModelProviderFilter.ReadyHere))
        assertTrue(providerMatches(xai, "", ModelProviderFilter.NeedsHostSetup))
        assertTrue(providerMatches(xai, "xai", ModelProviderFilter.NeedsHostSetup))
        assertFalse(providerMatches(xai, "xai", ModelProviderFilter.ReadyHere))
    }

    @Test
    fun model_matches_label_and_id_including_free_suffix() {
        val free = ModelChoice("deepseek/deepseek-r1:free", "DeepSeek R1")
        assertTrue(modelMatches(free, ""))
        assertTrue(modelMatches(free, "deepseek"))
        assertTrue(modelMatches(free, ":free"))
        assertTrue(modelMatches(free, ":free r1"))
        assertFalse(modelMatches(free, ":paid"))
    }

    @Test
    fun providers_view_keeps_runtime_order_and_counts() {
        val full = providersView(providers, picked = "anthropic", query = "", filter = null)
        assertFalse(full.narrowed)
        assertEquals("8 providers", full.count)
        assertEquals(8, full.total)
        assertEquals(5, full.ready)
        assertEquals(providers.map { it.id }, full.rows.map { it.provider.id })
        assertTrue(full.rows.none { it.kept })

        val one = providersView(providers.take(1), picked = "anthropic", query = "", filter = null)
        assertEquals("1 provider", one.count)

        // Matching query keeps runtime order without re-ranking.
        val open = providersView(providers, picked = "openai", query = "open", filter = null)
        assertTrue(open.narrowed)
        assertEquals("2 of 8", open.count)
        assertEquals(listOf("openai", "openrouter"), open.matches.map { it.id })
        assertEquals(listOf("openai", "openrouter"), open.rows.map { it.provider.id })
        assertTrue("matching picked row is not marked kept", open.rows.none { it.kept })
    }

    @Test
    fun providers_view_keeps_picked_row_first_and_uncounted_when_filtered_out() {
        val byQuery = providersView(providers, picked = "anthropic", query = "open", filter = null)
        assertEquals("2 of 8", byQuery.count)
        assertEquals(listOf("openai", "openrouter"), byQuery.matches.map { it.id })
        assertEquals(listOf("anthropic", "openai", "openrouter"), byQuery.rows.map { it.provider.id })
        assertEquals(listOf(true, false, false), byQuery.rows.map { it.kept })

        val byChip = providersView(providers, picked = "anthropic", query = "", filter = ModelProviderFilter.NeedsHostSetup)
        assertEquals("3 of 8", byChip.count)
        assertEquals(listOf("xai", "mistral", "bedrock"), byChip.matches.map { it.id })
        assertEquals(listOf("anthropic", "xai", "mistral", "bedrock"), byChip.rows.map { it.provider.id })
        assertEquals(listOf(true, false, false, false), byChip.rows.map { it.kept })

        val noMatch = providersView(providers, picked = "anthropic", query = "zzz", filter = null)
        assertEquals("0 of 8", noMatch.count)
        assertTrue(noMatch.matches.isEmpty())
        assertEquals(listOf(ProviderRow(providers.first(), kept = true)), noMatch.rows)
    }

    @Test
    fun models_view_keeps_order_counts_and_checked_row() {
        val models = (1..300).map { n ->
            when (n) {
                10 -> ModelChoice("anthropic/claude-opus-4", "Claude Opus 4")
                42 -> ModelChoice("deepseek/r1:free", "DeepSeek R1")
                100 -> ModelChoice("anthropic/claude-sonnet-4", "Claude Sonnet 4")
                150 -> ModelChoice("meta/llama-3:free", "Llama 3")
                200 -> ModelChoice("google/gemma-3:free", "Gemma 3")
                250 -> ModelChoice("qwen/qwen-3:free", "Qwen 3")
                else -> ModelChoice("vendor/model-$n", "Model $n")
            }
        }
        val full = modelsView(models, checked = { it.id == "anthropic/claude-opus-4" }, query = "")
        assertEquals("300 listed", full.count)
        assertEquals(300, full.matches.size)
        assertEquals(300, full.rows.size)
        assertTrue(full.rows.none { it.kept })

        val free = modelsView(models, checked = { it.id == "anthropic/claude-opus-4" }, query = ":free")
        assertEquals("4 of 300", free.count)
        assertEquals(
            listOf("deepseek/r1:free", "meta/llama-3:free", "google/gemma-3:free", "qwen/qwen-3:free"),
            free.matches.map { it.id },
        )
        assertEquals(
            listOf("anthropic/claude-opus-4", "deepseek/r1:free", "meta/llama-3:free", "google/gemma-3:free", "qwen/qwen-3:free"),
            free.rows.map { it.choice.id },
        )
        assertEquals(listOf(true, false, false, false, false), free.rows.map { it.kept })

        // Checked model that matches stays in runtime order and is not marked kept.
        val claude = modelsView(models, checked = { it.id == "anthropic/claude-sonnet-4" }, query = "claude")
        assertEquals("2 of 300", claude.count)
        assertEquals(listOf("anthropic/claude-opus-4", "anthropic/claude-sonnet-4"), claude.rows.map { it.choice.id })
        assertTrue(claude.rows.none { it.kept })

        // Checked model when nothing matches is still kept first and uncounted.
        val none = modelsView(models, checked = { it.id == "anthropic/claude-opus-4" }, query = "nonexistent")
        assertEquals("0 of 300", none.count)
        assertTrue(none.matches.isEmpty())
        assertEquals(listOf("anthropic/claude-opus-4"), none.rows.map { it.choice.id })
        assertEquals(listOf(true), none.rows.map { it.kept })
    }

    @Test
    fun matches_keeps_its_spans_when_the_same_text_is_read_again() {
        val text = "Cafe\u0301  \u0130stanbul Mod\u00e8le"
        val first = matches(text, "cafe")
        val second = matches(text, "istanbul modele")
        assertEquals(listOf("Cafe\u0301"), first.map { text.substring(it.first, it.last + 1) })
        assertEquals(listOf("\u0130stanbul", "Mod\u00e8le"), second.map { text.substring(it.first, it.last + 1) })
    }
}
