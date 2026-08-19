package net.actinium.client.gui;

import net.actinium.client.gui.control.ActiniumButton;
import net.actinium.dev.ShaderCorpus;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class ShaderPackScreen extends Screen {
    private static final int MARGIN = 20;
    private static final int TITLE_Y = 12;
    private static final int LIST_Y = 34;
    private static final int ROW_HEIGHT = 18;
    private static final int BUTTON_BAR_HEIGHT = 32;
    private static final int LINE_HEIGHT = 10;

    private final Screen parent;
    private final Path folder;
    private final List<Pack> packs = new ArrayList<>();
    private final List<PackRow> rows = new ArrayList<>();

    private int selected = -1;
    private int scroll;
    private Analysis analysis;
    private volatile boolean analysing;

    private ActiniumButton analyseButton;

    private record Pack(String name, Path path) {
    }

    private record Analysis(String pack, int converted, int total, List<String> reasons) {
    }

    public ShaderPackScreen(Screen parent) {
        super(Component.translatable("actinium.shaderpacks.title"));
        this.parent = parent;
        this.folder = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath()
                .resolve("shaderpacks");
    }

    @Override
    protected void init() {
        refresh();

        int buttonY = height - BUTTON_BAR_HEIGHT + 6;
        int buttonWidth = 96;
        int gap = 6;

        addWidget(new ActiniumButton(MARGIN, buttonY, buttonWidth, 20,
                Component.translatable("actinium.shaderpacks.folder"),
                ActiniumButton.Style.SECONDARY, this::openFolder));
        addWidget(new ActiniumButton(MARGIN + buttonWidth + gap, buttonY, 64, 20,
                Component.translatable("actinium.shaderpacks.refresh"),
                ActiniumButton.Style.SECONDARY, this::refreshAndRebuild));

        int doneX = width - MARGIN - 64;
        int analyseX = doneX - buttonWidth - gap;
        analyseButton = addWidget(new ActiniumButton(analyseX, buttonY, buttonWidth, 20,
                Component.translatable("actinium.shaderpacks.analyse"),
                ActiniumButton.Style.SECONDARY, this::analyseSelected));
        addWidget(new ActiniumButton(doneX, buttonY, 64, 20, CommonComponents.GUI_DONE,
                ActiniumButton.Style.PRIMARY, this::onClose));

        analyseButton.active = selected >= 0;
    }

    private void refresh() {
        packs.clear();
        rows.clear();

        if (Files.isDirectory(folder)) {
            try (Stream<Path> entries = Files.list(folder)) {
                entries.filter(path -> Files.isDirectory(path)
                                || path.getFileName().toString().endsWith(".zip"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()
                                .toLowerCase(java.util.Locale.ROOT)))
                        .forEach(path -> packs.add(new Pack(path.getFileName().toString(), path)));
            } catch (IOException ignored) {
                packs.clear();
            }
        }

        int listWidth = listWidth();
        int y = LIST_Y - scroll;
        for (int i = 0; i < packs.size(); i++) {
            PackRow row = new PackRow(MARGIN, y, listWidth, packs.get(i).name(), i);
            rows.add(row);
            addWidget(row);
            y += ROW_HEIGHT;
        }
    }

    private void refreshAndRebuild() {
        analysis = null;
        selected = -1;
        scroll = 0;
        rebuildWidgets();
    }

    private int listWidth() {
        return Math.max(140, (width - MARGIN * 3) / 2);
    }

    private void openFolder() {
        try {
            Files.createDirectories(folder);
        } catch (IOException ignored) {
            return;
        }
        Util.getPlatform().openFile(folder.toFile());
    }

    private void analyseSelected() {
        if (selected < 0 || selected >= packs.size() || analysing) {
            return;
        }
        Pack pack = packs.get(selected);
        analysing = true;
        analysis = null;

        Thread worker = new Thread(() -> {
            try {
                ShaderCorpus.Report report = ShaderCorpus.convert(pack.path());
                Map<String, Integer> counted = new LinkedHashMap<>();
                for (ShaderCorpus.Failure failure : report.failures()) {
                    counted.merge(shorten(failure.reason()), 1, Integer::sum);
                }
                List<String> reasons = counted.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .limit(3)
                        .map(entry -> entry.getValue() + "x " + entry.getKey())
                        .toList();
                analysis = new Analysis(pack.name(), report.converted(), report.total(), reasons);
            } catch (RuntimeException e) {
                analysis = new Analysis(pack.name(), 0, 0, List.of(String.valueOf(e.getMessage())));
            } finally {
                analysing = false;
            }
        }, "Actinium shader pack analysis");
        worker.setDaemon(true);
        worker.start();
    }

    private static String shorten(String reason) {
        String text = reason.contains("error: ") ? reason.substring(reason.indexOf("error: ") + 7) : reason;
        return text.length() > 60 ? text.substring(0, 57) + "..." : text;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(font, title, width / 2, TITLE_Y, Theme.TEXT_HEADER);

        int listWidth = listWidth();
        int listBottom = height - BUTTON_BAR_HEIGHT - 4;
        panel(graphics, MARGIN - 4, LIST_Y - 4, listWidth + 8, listBottom - LIST_Y + 8);

        int detailX = MARGIN * 2 + listWidth;
        int detailWidth = width - detailX - MARGIN;
        panel(graphics, detailX - 4, LIST_Y - 4, detailWidth + 8, listBottom - LIST_Y + 8);

        graphics.enableScissor(MARGIN, LIST_Y, MARGIN + listWidth, listBottom);
        if (packs.isEmpty()) {
            graphics.drawString(font, Component.translatable("actinium.shaderpacks.empty"),
                    MARGIN + 4, LIST_Y + 4, Theme.TEXT_DIM);
        } else {
            for (PackRow row : rows) {
                row.render(graphics, mouseX, mouseY, partialTick);
            }
        }
        graphics.disableScissor();

        renderDetails(graphics, detailX, detailWidth, listBottom);

        for (var child : children()) {
            if (child instanceof ActiniumButton button) {
                button.render(graphics, mouseX, mouseY, partialTick);
            }
        }
    }

    private void renderDetails(GuiGraphics graphics, int x, int detailWidth, int bottom) {
        int y = LIST_Y + 2;
        if (selected < 0) {
            graphics.drawString(font, Component.translatable("actinium.shaderpacks.pick"),
                    x, y, Theme.TEXT_DIM);
        } else {
            graphics.drawString(font, packs.get(selected).name(), x, y, Theme.TEXT_HEADER);
            y += LINE_HEIGHT + 4;

            if (analysing) {
                graphics.drawString(font, Component.translatable("actinium.shaderpacks.working"),
                        x, y, Theme.TEXT_DIM);
            } else if (analysis != null && analysis.pack().equals(packs.get(selected).name())) {
                graphics.drawString(font, Component.translatable("actinium.shaderpacks.result",
                                analysis.converted(), analysis.total()),
                        x, y, analysis.converted() == analysis.total() ? Theme.ACCENT : Theme.WARNING);
                y += LINE_HEIGHT + 4;
                for (String reason : analysis.reasons()) {
                    for (var line : font.split(Component.literal(reason), detailWidth)) {
                        graphics.drawString(font, line, x, y, Theme.TEXT_DIM);
                        y += LINE_HEIGHT;
                    }
                }
            } else {
                graphics.drawString(font, Component.translatable("actinium.shaderpacks.unchecked"),
                        x, y, Theme.TEXT_DIM);
            }
        }

        int noteY = bottom - LINE_HEIGHT * 3;
        for (var line : font.split(Component.translatable("actinium.shaderpacks.note"), detailWidth)) {
            graphics.drawString(font, line, x, noteY, Theme.TEXT_DISABLED);
            noteY += LINE_HEIGHT;
        }
    }

    private static void panel(GuiGraphics graphics, int x, int y, int panelWidth, int panelHeight) {
        graphics.fill(x, y, x + panelWidth, y + panelHeight, Theme.PANEL_BG);
        graphics.renderOutline(x, y, panelWidth, panelHeight, Theme.PANEL_BORDER);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int viewport = height - BUTTON_BAR_HEIGHT - 4 - LIST_Y;
        int maxScroll = Math.max(0, packs.size() * ROW_HEIGHT - viewport);
        int updated = Math.clamp(scroll - (int) (scrollY * ROW_HEIGHT), 0, maxScroll);
        if (updated != scroll) {
            scroll = updated;
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private final class PackRow extends AbstractWidget {
        private final int index;

        private PackRow(int x, int y, int rowWidth, String name, int index) {
            super(x, y, rowWidth, ROW_HEIGHT, Component.literal(name));
            this.index = index;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean chosen = index == selected;
            if (chosen) {
                graphics.fill(getX(), getY(), getX() + width, getY() + height, Theme.ACCENT_DIM);
            } else if (isHovered()) {
                graphics.fill(getX(), getY(), getX() + width, getY() + height, Theme.ROW_HOVER);
            }
            graphics.drawString(font, getMessage(), getX() + 6, getY() + 5,
                    chosen ? Theme.TEXT_HEADER : Theme.TEXT);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            selected = index;
            analysis = null;
            if (analyseButton != null) {
                analyseButton.active = true;
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
        }
    }
}
