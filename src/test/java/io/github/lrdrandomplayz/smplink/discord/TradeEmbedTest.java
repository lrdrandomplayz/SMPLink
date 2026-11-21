package io.github.lrdrandomplayz.smplink.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.MessageEmbed;
import io.github.lrdrandomplayz.smplink.db.TradeRepository;
import io.github.lrdrandomplayz.smplink.inventory.ItemView;
import org.junit.jupiter.api.Test;

class TradeEmbedTest {

    private static final long NOW = 1_790_000_000_000L;
    private static final String FOOTER = "This is only a medium for trading, you may get scammed!";

    private static final String ROLE = "100000000000000097";

    private static TradeRepository.Trade trade(String offerItems, String wantItems, String middlemanBy) {
        return trade(offerItems, wantItems, middlemanBy, null, null);
    }

    private static TradeRepository.Trade trade(String offerItems, String wantItems, String middlemanBy, String middlemanId,
                                               String threadId) {
        return trade(offerItems, wantItems, middlemanBy, middlemanId, threadId, middlemanBy == null ? 0 : 1, null);
    }

    private static TradeRepository.Trade trade(String offerItems, String wantItems, String middlemanBy, String middlemanId,
                                               String threadId, int requests, String cancelledBy) {
        return new TradeRepository.Trade("5", "9", "100000000000000099", "Steve", NOW, "x", "y", offerItems, wantItems,
                "open", null, null, middlemanBy, middlemanId, threadId, false, requests, cancelledBy, null);
    }

    private static Container card(String offerItems, String wantItems, String middlemanBy, String closedTitle, String closedNote) {
        return TradeBoard.post(trade(offerItems, wantItems, middlemanBy), 2, NOW + 7L * 24 * 60 * 60 * 1000, FOOTER, ROLE, true,
                closedTitle, closedNote);
    }

    private static Container openCard() {
        return card("diamond:64", "elytra:1", null, null, null);
    }

    /** Every piece of text on the card, in order. */
    private static String text(Container card) {
        StringBuilder text = new StringBuilder();
        for (var part : card.getComponents()) {
            if (part instanceof TextDisplay display) {
                text.append(display.getContent()).append('\n');
            } else if (part instanceof Section section) {
                section.getContentComponents().forEach(c -> text.append(((TextDisplay) c).getContent()).append('\n'));
            }
        }
        return text.toString();
    }

    private static List<Button> buttons(Container card) {
        return card.getComponents().stream().filter(c -> c instanceof ActionRow)
                .flatMap(c -> ((ActionRow) c).getButtons().stream()).toList();
    }

    @Test
    void theButtonsSitInsideTheCard() {
        Container card = openCard();
        assertEquals(List.of("I'm interested (2)", "Request Middleman", "Mark traded", "Remove"),
                buttons(card).stream().map(Button::getLabel).toList());
        assertEquals(Embeds.BRAND, card.getAccentColorRaw());
        String text = text(card);
        assertTrue(text.contains("💬 Press **I'm interested** to open a private chat with <@100000000000000099>"), text);
        assertTrue(text.contains("-# ⚠️ " + FOOTER), text);
        assertTrue(card.getComponents().stream().anyMatch(c -> c instanceof MediaGallery), "the trade picture");
    }

    @Test
    void eachItemHasAQuantityLineUnderIt() {
        String text = text(card("diamond:64,gold_ingot:16", "elytra:1,totem_of_undying:2", null, null, null));
        assertTrue(text.contains("### 📦 Offering\n**Diamond**\nQuantity: **64**\n\n**Gold Ingot**\nQuantity: **16**"), text);
        assertTrue(text.contains("### 🎯 Looking for\n**Elytra**\nQuantity: **1**\n\n**Totem Of Undying**\nQuantity: **2**"), text);
    }

    @Test
    void requestingAMiddlemanIsNotedAndDisablesTheButton() {
        Container card = card("diamond:64", "elytra:1", "123", null, null);
        assertTrue(text(card).contains("🛡️ **Middleman requested** by <@123>"), text(card));
        // The role is pinged by a separate reply; the card itself doesn't tag it.
        assertFalse(text(card).contains("<@&"), text(card));
        // Until a middleman claims it, the button is for middlemen to take the trade.
        Button claim = buttons(card).get(1);
        assertEquals("I'll middleman this", claim.getLabel());
        assertFalse(claim.isDisabled());
        assertTrue(text(card).contains("waiting for a middleman to claim it"), text(card));
        // Without a middleman role there's no button.
        Container noRole = TradeBoard.post(trade("diamond:64", "elytra:1", null), 0, NOW, FOOTER, "", false, null, null);
        assertEquals(List.of("I'm interested", "Mark traded", "Remove"), buttons(noRole).stream().map(Button::getLabel).toList());
    }

    @Test
    void aClosedCardIsGreyWithItsNoteAndNoButtons() {
        Container card = TradeBoard.post(trade("diamond:64", "elytra:1", "123", "777", null), 0, NOW, FOOTER, ROLE, true,
                "✅ Traded", "Traded with <@2> · ✅ confirmed");
        assertEquals(Embeds.MUTED, card.getAccentColorRaw());
        assertTrue(buttons(card).isEmpty());
        String text = text(card);
        assertTrue(text.contains("## ✅ Traded"), text);
        assertTrue(text.contains("**Traded with <@2> · ✅ confirmed**"), text);
        assertFalse(text.contains("private chat"), text);
        assertTrue(text.contains("🛡️ **Middleman:** <@777>"), "the middleman who took it stays on record");
    }

    /** A post from before 1.20, which was a plain embed; those are still closed the old way. */
    private static MessageEmbed legacyPost() {
        return Embeds.base(Embeds.BRAND).setTitle("🔁 Trade offer")
                .setDescription("Posted by <@100000000000000099> · 🎮 `Steve`")
                .addField("📦 Offering", "**Diamond**", true)
                .addField("⏳ Expires", "<t:1:R>", false)
                .addField("💬 Interested?", "💬 **DM <@100000000000000099> to negotiate**", false)
                .setFooter("⚠️ " + FOOTER).build();
    }

    @Test
    void oldEmbedPostsAreStillClosedTheOldWay() {
        MessageEmbed closed = TradeBoard.closed(legacyPost(), "✅ Traded", "Marked traded");
        assertEquals(Embeds.MUTED, closed.getColorRaw());
        assertEquals("✅ Traded", closed.getTitle());
        assertFalse(closed.getFields().stream().anyMatch(f -> f.getValue().contains("negotiate")));
        assertFalse(closed.getFields().stream().anyMatch(f -> f.getName().contains("Expires")));
        assertTrue(closed.getFields().stream().anyMatch(f -> f.getName().contains("Offering")));

        MessageEmbed twice = TradeBoard.closed(closed, "✅ Traded", "Traded with <@2> · confirmed");
        assertEquals("Posted by <@100000000000000099> · 🎮 `Steve`\n**Traded with <@2> · confirmed**", twice.getDescription());
    }

    @Test
    void autocompleteFindsItemsByAnyWord() {
        List<String> ids = List.of("diamond", "diamond_sword", "diamond_block", "golden_apple", "enchanted_golden_apple", "apple");
        assertEquals(List.of("diamond", "diamond_block", "diamond_sword"), TradeBoard.matchingItems(ids, "diam"));
        // "apple" matches the word at the start or after an underscore, shortest first.
        assertEquals(List.of("apple", "golden_apple", "enchanted_golden_apple"), TradeBoard.matchingItems(ids, "Apple"));
        assertEquals(List.of("golden_apple", "enchanted_golden_apple"), TradeBoard.matchingItems(ids, "golden apple"));
    }

    @Test
    void itemsRoundTripAndSummarise() {
        List<TradeBoard.Stack> stacks = List.of(new TradeBoard.Stack("diamond", 64), new TradeBoard.Stack("enchanted_book", 1));
        assertEquals("diamond:64,enchanted_book:1", TradeBoard.encode(stacks));
        assertEquals(stacks, TradeBoard.decode("diamond:64,enchanted_book:1"));
        assertEquals("64 × Diamond, 1 × Enchanted Book", TradeBoard.summary("diamond:64,enchanted_book:1", "ignored"));
        assertEquals("some text", TradeBoard.summary(null, "some\n text"));
    }

    @Test
    void drawsAVillagerStyleTrade() throws Exception {
        InventoryRenderer renderer = new InventoryRenderer(null, java.util.List.of("netherite_sword", "oak_log", "shield", "diamond_helmet", "diamond", "elytra", "enchanted_book", "totem_of_undying"));
        assertTrue(renderer.hasItem("diamond") && renderer.hasItem("elytra"));
        assertFalse(renderer.hasItem("not_an_item"));
        byte[] png = renderer.renderTrade(new ItemView(0, "diamond", 64, null, null, 0, 0),
                new ItemView(0, "enchanted_book", 1, null, java.util.Map.of("glint", 1), 0, 0),
                new ItemView(0, "elytra", 1, null, null, 0, 0));
        Path out = Path.of("build", "test-output", "trade.png");
        Files.createDirectories(out.getParent());
        Files.write(out, png);
        assertTrue(png.length > 500);

        // One offered stack: the second input slot is left out and the picture is narrower.
        byte[] single = renderer.renderTrade(new ItemView(0, "diamond", 64, null, null, 0, 0), null,
                new ItemView(0, "elytra", 1, null, null, 0, 0));
        Files.write(out.resolveSibling("trade-single.png"), single);
        assertTrue(single.length > 500);
    }

    @Test
    void onlyRealItemsAreAccepted() {
        java.util.Set<String> items = java.util.Set.of("diamond", "diamond_sword", "glass", "elytra");
        assertEquals(java.util.Optional.of("diamond_sword"), TradeBoard.resolveItem(" Diamond Sword ", items::contains));
        assertEquals(java.util.Optional.of("elytra"), TradeBoard.resolveItem("minecraft:elytra", items::contains));
        // Plurals are forgiven, as long as the result is a real item.
        assertEquals(java.util.Optional.of("diamond"), TradeBoard.resolveItem("diamonds", items::contains));
        assertEquals(java.util.Optional.of("glass"), TradeBoard.resolveItem("glass", items::contains));
        assertEquals(java.util.Optional.empty(), TradeBoard.resolveItem("dimond", items::contains));
        assertEquals(java.util.Optional.empty(), TradeBoard.resolveItem("   ", items::contains));
    }

    @Test
    void quantitiesMustBeWholeNumbersInRange() {
        assertEquals(64, TradeBoard.parseQuantity(" 64 "));
        assertEquals(-1, TradeBoard.parseQuantity("0"));
        assertEquals(-1, TradeBoard.parseQuantity("1000"));
        assertEquals(-1, TradeBoard.parseQuantity("a stack"));
    }

    @Test
    void drawsASecondWantedItem() throws Exception {
        InventoryRenderer renderer = new InventoryRenderer(null, java.util.List.of("netherite_sword", "oak_log", "shield", "diamond_helmet", "diamond", "elytra", "enchanted_book", "totem_of_undying"));
        byte[] png = renderer.renderTrade(new ItemView(0, "diamond", 64, null, null, 0, 0), null,
                new ItemView(0, "elytra", 1, null, null, 0, 0), new ItemView(0, "totem_of_undying", 2, null, null, 0, 0));
        Path out = Path.of("build", "test-output", "trade-two-wants.png");
        Files.createDirectories(out.getParent());
        Files.write(out, png);
        assertTrue(png.length > 500);
    }

    @Test
    void aClaimedTradeShowsTheMiddlemanAndTheirThread() {
        Container card = TradeBoard.post(trade("diamond:64", "elytra:1", "123", "777", "888"), 0, NOW, FOOTER, ROLE, false, null, null);
        assertTrue(text(card).contains("🛡️ **Middleman:** <@777> (requested by <@123>) · <#888>"), text(card));
        Button claimed = buttons(card).get(1);
        assertEquals("Middleman on it", claimed.getLabel());
        assertTrue(claimed.isDisabled());
        assertEquals("Cancel middleman", buttons(card).get(2).getLabel());
    }

    @Test
    void ratingSummaryShowsBothRatings() {
        String summary = TradeReviews.ratingSummary(java.util.Map.of("trade", true, "middleman", false), "2", "3");
        assertTrue(summary.contains("👍 vouched for <@2>"), summary);
        assertTrue(summary.contains("🚩 reported middleman <@3>"), summary);
        assertEquals("", Embeds.middlemanRep(new TradeRepository.Rep(0, 0, 0)));
        assertEquals("\n🛡️ Middleman **+4** · 5 trades", Embeds.middlemanRep(new TradeRepository.Rep(4, 0, 5)));
    }

    @Test
    void afterACancelTheCardSaysSoAndTheRequestLimitHolds() {
        Container again = TradeBoard.post(trade("diamond:64", "elytra:1", null, null, null, 1, "123"), 0, NOW, FOOTER, ROLE, false, null, null);
        assertTrue(text(again).contains("-# 🛡️ Middleman cancelled by <@123>"), text(again));
        Button request = buttons(again).get(1);
        assertEquals("Request Middleman", request.getLabel());
        assertFalse(request.isDisabled());

        Container spent = TradeBoard.post(trade("diamond:64", "elytra:1", null, null, null, 2, "123"), 0, NOW, FOOTER, ROLE, false, null, null);
        Button limit = buttons(spent).get(1);
        assertEquals("Middleman limit reached", limit.getLabel());
        assertTrue(limit.isDisabled());
    }

    private static TradeRepository.Trade stored(String status, String partner, Boolean confirmed) {
        return new TradeRepository.Trade("5", "9", "1", "Seller", NOW, "64 diamonds", "an elytra", null, null,
                status, partner, confirmed, null, null, null, false, 0, null, null);
    }

    @Test
    void oldPostsAreRedrawnWithTheRightState() {
        assertEquals(null, TradeBoard.closedState(stored("open", null, null)));
        assertEquals("Traded with <@2> · ✅ confirmed", TradeBoard.closedState(stored("traded", "2", true))[1]);
        assertEquals("Traded with <@2> · " + Glyph.TIME + " waiting for them to confirm", TradeBoard.closedState(stored("traded", "2", null))[1]);
        assertTrue(TradeBoard.closedState(stored("traded", "2", false))[1].contains("didn't happen"));
        assertEquals("Traded", TradeBoard.closedState(stored("traded", null, null))[1]);
        assertEquals(Glyph.TIME + " Expired", TradeBoard.closedState(stored("expired", null, null))[0]);
    }

    @Test
    void tradesFromBeforeItemsWereRequiredShowTheirText() {
        assertEquals("64 diamonds", TradeBoard.itemsOrText(null, "64 diamonds"));
        assertEquals("**Diamond**\nQuantity: **64**", TradeBoard.itemsOrText("diamond:64", "ignored"));
        Container card = TradeBoard.post(stored("open", null, null), 0, NOW, FOOTER, ROLE, false, null, null);
        assertTrue(text(card).contains("### 📦 Offering\n64 diamonds"), text(card));
    }
}
