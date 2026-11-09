package com.example.game

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.ui.MainViewModel
import com.example.voice.VoiceCommandAction
import com.example.voice.VoiceCommandDispatcher
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdventureEngineTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AppDatabase.clearInstanceForTesting()
    }

    @After
    fun tearDown() {
        AppDatabase.clearInstanceForTesting()
    }

    @Test
    fun proceduralLoot_isDeterministicForSeed_andProducesValidRarityColors() {
        val itemA1 = AdventureEngine.generateProceduralLoot(424242L)
        val itemA2 = AdventureEngine.generateProceduralLoot(424242L)
        assertEquals("Same seed must produce identical loot name", itemA1.name, itemA2.name)
        assertEquals("Same seed must produce identical rarity hex", itemA1.rarityColorHex, itemA2.rarityColorHex)
        assertEquals("Same seed must produce identical quantity", itemA1.quantity, itemA2.quantity)

        val validHexes = setOf(
            InventoryItem.RARITY_COMMON_HEX,
            InventoryItem.RARITY_RARE_HEX,
            InventoryItem.RARITY_EPIC_HEX,
            InventoryItem.RARITY_LEGENDARY_HEX
        )
        for (seed in 1L..50L) {
            val item = AdventureEngine.generateProceduralLoot(seed * 9973L)
            assertTrue("Generated item name must not be blank", item.name.isNotBlank())
            assertTrue(
                "Generated rarity hex ${item.rarityColorHex} must be one of the 4 rarity tiers",
                item.rarityColorHex in validHexes
            )
        }
    }

    @Test
    fun inlineMarkers_parseQuestAddQuestCompleteAndLootDrop_andMutateAdventureStateWithAutoSave() = runBlocking {
        val db = AppDatabase.buildInMemory(app)
        val dao = db.adventureGameDao()

        val rawResponse = """
            You step through the shimmering portal into the Vault of Echoes.
            [QUEST_ADD: {"id":"quest_echo_vault","title":"Seal the Echo Breach","description":"Stabilize the three harmonic pylons.","xpReward":350}]
            [QUEST_COMPLETE: {"id":"quest_astral_beacon","title":"Awaken the Astral Beacon"}]
            [LOOT_DROP: {"seed":777888}]
        """.trimIndent()

        val parsed = AdventureEngine.parseInlineMarkers(rawResponse)
        assertFalse("Cleaned prose must strip inline marker tags", parsed.cleanedText.contains("[QUEST_ADD"))
        assertEquals("Expected 3 parsed inline RPG events", 3, parsed.events.size)

        val initialState = AdventureGameState(chatId = 10L, isAdventureModeEnabled = true)
        val (updatedState, questCompleted) = AdventureEngine.applyInlineEventsToState(initialState, parsed.events)

        assertTrue("Completing quest_astral_beacon must set questCompleted = true", questCompleted)
        assertTrue(
            "New quest quest_echo_vault must be added to quests list",
            updatedState.quests.any { it.id == "quest_echo_vault" && it.xpReward == 350 }
        )
        assertTrue(
            "quest_astral_beacon must be marked completed",
            updatedState.quests.first { it.id == "quest_astral_beacon" }.isCompleted
        )
        assertTrue(
            "Total XP must increase by quest_astral_beacon reward (250 XP)",
            updatedState.totalXp >= initialState.totalXp + 250
        )
        assertTrue(
            "Procedural loot from seed 777888 must be added to inventory",
            updatedState.inventory.size > initialState.inventory.size
        )

        // Verify Room DAO persistence & round-trip restoration
        dao.upsertState(updatedState.toEntity(targetChatId = 10L))
        val loadedEntity = dao.getStateForChatSync(10L)
        assertNotNull(loadedEntity)
        val restored = loadedEntity!!.toDomainState()
        assertEquals(updatedState.totalXp, restored.totalXp)
        assertEquals(updatedState.quests.size, restored.quests.size)
        assertEquals(updatedState.inventory.size, restored.inventory.size)
        db.close()
    }

    @Test
    fun documentLoreExtraction_andSystemPromptInjection_andSaveSentinel20Minutes() {
        val viewModel = MainViewModel(app)
        try {
            // 1. Create a temporary Markdown lore file and attach it to Adventure
            val loreFile = File(app.cacheDir, "elder_star_lore.md")
            loreFile.writeText("# The Elder Star Concordat\nThe Citadel of Vaelor is guarded by the Chrono-Wardens.")
            val loreUri = Uri.fromFile(loreFile)

            viewModel.attachDocumentToAdventure(loreUri, "text/markdown")
            Thread.sleep(150L)
            shadowOf(Looper.getMainLooper()).idle()

            val stateAfterLore = viewModel.adventureState.value
            assertTrue(
                "Attaching lore document must inject text into GameSettings.loreContext",
                stateAfterLore.settings.loreContext.contains("Citadel of Vaelor")
            )

            // 2. Verify Game Master System Prompt includes difficulty, quests, inventory, and lore
            val gmPrompt = AdventureEngine.buildGameMasterSystemPrompt(
                baseSystemInstruction = "You are Gemini.",
                state = stateAfterLore.copy(isAdventureModeEnabled = true)
            )
            assertTrue(gmPrompt.contains("GEMINI'S INFINITE ADVENTURE ENGINE"))
            assertTrue(gmPrompt.contains("Citadel of Vaelor"))
            assertTrue(gmPrompt.contains("[QUEST_ADD:"))
            assertTrue(gmPrompt.contains("[LOOT_DROP:"))

            // 3. Verify 20-minute Save Sentinel triggers snackbar state after 1200+ seconds
            assertFalse(viewModel.adventureState.value.isSaveSentinelAlertVisible)
            viewModel.advanceAdventurePlayTimerSecondsForTest(1205L)
            assertTrue(
                "20+ minutes of unsaved play must activate Save Sentinel alert",
                viewModel.adventureState.value.isSaveSentinelAlertVisible
            )

            // Manual save resets unsaved timer and clears Save Sentinel alert
            viewModel.saveAdventureGameManually()
            assertFalse(
                "Manual save must clear Save Sentinel alert",
                viewModel.adventureState.value.isSaveSentinelAlertVisible
            )
            assertEquals(0L, viewModel.adventureState.value.unsavedPlaySeconds)
        } finally {
            viewModel.cleanShutdown(null)
        }
    }

    @Test
    fun voiceCommandDispatcher_parsesAndExecutesAdventureDrawerAndSaveCommands() {
        val viewModel = MainViewModel(app)
        try {
            val dispatcher = viewModel.voiceCommandDispatcher

            // 1. "open inventory" / "show inventory"
            val openInv = dispatcher.parseCommand("Hey Gemini open inventory")
            assertEquals(VoiceCommandAction.OpenAdventureDrawer(AdventureDrawerTab.INVENTORY), openInv)
            assertTrue(viewModel.executeInChatVoiceActionCommand("open inventory"))
            assertTrue(viewModel.adventureState.value.isDrawerOpen)
            assertEquals(AdventureDrawerTab.INVENTORY, viewModel.adventureState.value.activeDrawerTab)

            // 2. "check quests" / "show quests"
            val checkQuests = dispatcher.parseCommand("check quests")
            assertEquals(VoiceCommandAction.OpenAdventureDrawer(AdventureDrawerTab.QUESTS), checkQuests)
            assertTrue(viewModel.executeInChatVoiceActionCommand("show quests"))
            assertTrue(viewModel.adventureState.value.isDrawerOpen)
            assertEquals(AdventureDrawerTab.QUESTS, viewModel.adventureState.value.activeDrawerTab)

            // 3. "save game" / "save adventure"
            viewModel.advanceAdventurePlayTimerSecondsForTest(1300L)
            assertTrue(viewModel.adventureState.value.isSaveSentinelAlertVisible)
            val saveAction = dispatcher.parseCommand("save adventure")
            assertEquals(VoiceCommandAction.SaveAdventureGame, saveAction)
            assertTrue(viewModel.executeInChatVoiceActionCommand("save game"))
            assertFalse(viewModel.adventureState.value.isSaveSentinelAlertVisible)
            assertTrue(viewModel.adventureState.value.lastSavedAt > 0L)

            // 4. "close drawer"
            val closeAction = dispatcher.parseCommand("close drawer")
            assertEquals(VoiceCommandAction.CloseAdventureDrawer, closeAction)
            assertTrue(viewModel.executeInChatVoiceActionCommand("close drawer"))
            assertFalse(viewModel.adventureState.value.isDrawerOpen)
        } finally {
            viewModel.cleanShutdown(null)
        }
    }

    @Test
    fun questFilteringToggle_andVisualRarityIndicatorSystem_workAsExpected() = runBlocking {
        // 1. Verify RarityTier color mapping and pulsing aura behavior (Common: Grey, Rare: Blue, Epic: Purple, Legendary: Orange)
        val commonItem = InventoryItem(
            id = "item_common",
            name = "Iron Ration",
            category = "Consumable",
            iconName = "Bolt",
            rarityColorHex = InventoryItem.RARITY_COMMON_HEX,
            description = "Standard ration.",
            quantity = 3
        )
        val rareItem = commonItem.copy(id = "item_rare", rarityColorHex = InventoryItem.RARITY_RARE_HEX)
        val epicItem = commonItem.copy(id = "item_epic", rarityColorHex = InventoryItem.RARITY_EPIC_HEX)
        val legendaryItem = commonItem.copy(id = "item_legendary", rarityColorHex = InventoryItem.RARITY_LEGENDARY_HEX)

        assertEquals(RarityTier.COMMON, commonItem.rarityTier)
        assertFalse("Common items must use static glow (hasPulsingAura = false)", commonItem.rarityTier.hasPulsingAura)
        assertEquals("#94A3B8", commonItem.rarityTier.primaryColorHex)

        assertEquals(RarityTier.RARE, rareItem.rarityTier)
        assertFalse("Rare items must use static glow (hasPulsingAura = false)", rareItem.rarityTier.hasPulsingAura)
        assertEquals("#38BDF8", rareItem.rarityTier.primaryColorHex)

        assertEquals(RarityTier.EPIC, epicItem.rarityTier)
        assertTrue("Epic items must use pulsing aura (hasPulsingAura = true)", epicItem.rarityTier.hasPulsingAura)
        assertEquals("#C084FC", epicItem.rarityTier.primaryColorHex)

        assertEquals(RarityTier.LEGENDARY, legendaryItem.rarityTier)
        assertTrue("Legendary items must use pulsing aura (hasPulsingAura = true)", legendaryItem.rarityTier.hasPulsingAura)
        assertEquals("#F59E0B", legendaryItem.rarityTier.primaryColorHex)

        // 2. Verify QuestFilterMode ('All', 'Active', 'Completed') filtering and Room persistence
        val viewModel = MainViewModel(app)
        try {
            viewModel.addCustomAdventureQuest("Explore the Sunken Spire", "Locate the ancient astrolabe.", 300)
            val initialQuests = viewModel.adventureState.value.quests
            val completedBefore = viewModel.adventureState.value.completedQuestCount
            assertTrue("Expected at least 3 quests", initialQuests.size >= 3)

            // Mark the newly added active quest completed
            val targetQuestId = initialQuests.first { !it.isCompleted }.id
            viewModel.toggleAdventureQuestCompleted(targetQuestId)

            val stateAfterComplete = viewModel.adventureState.value
            val totalCount = stateAfterComplete.quests.size
            val completedCount = stateAfterComplete.completedQuestCount
            val activeCount = stateAfterComplete.activeQuestCount
            assertEquals(completedBefore + 1, completedCount)
            assertEquals(totalCount - completedCount, activeCount)

            // Filter by ALL
            viewModel.setAdventureQuestFilter(QuestFilterMode.ALL)
            assertEquals(QuestFilterMode.ALL, viewModel.adventureState.value.questFilter)
            assertEquals(totalCount, viewModel.adventureState.value.filteredQuests.size)

            // Filter by ACTIVE
            viewModel.setAdventureQuestFilter(QuestFilterMode.ACTIVE)
            assertEquals(QuestFilterMode.ACTIVE, viewModel.adventureState.value.questFilter)
            assertEquals(activeCount, viewModel.adventureState.value.filteredQuests.size)
            assertTrue(viewModel.adventureState.value.filteredQuests.none { it.isCompleted })

            // Filter by COMPLETED
            viewModel.setAdventureQuestFilter(QuestFilterMode.COMPLETED)
            assertEquals(QuestFilterMode.COMPLETED, viewModel.adventureState.value.questFilter)
            assertEquals(completedCount, viewModel.adventureState.value.filteredQuests.size)
            assertTrue(viewModel.adventureState.value.filteredQuests.all { it.isCompleted })

            // Verify GameSettings JSON serialization & Room entity round-trip preserves questFilter
            val entity = viewModel.adventureState.value.toEntity(targetChatId = 77L)
            val restoredState = entity.toDomainState()
            assertEquals(QuestFilterMode.COMPLETED, restoredState.questFilter)
            assertEquals(completedCount, restoredState.filteredQuests.size)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            viewModel.cleanShutdown(null)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
