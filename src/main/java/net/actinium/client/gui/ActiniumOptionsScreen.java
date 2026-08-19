package net.actinium.client.gui;

import net.actinium.client.gui.control.ActiniumButton;
import net.actinium.client.gui.control.ControlElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class ActiniumOptionsScreen extends Screen {
    private static final int TAB_Y = 8;
    private static final int TAB_HEIGHT = 18;
    private static final int CONTENT_Y = TAB_Y + TAB_HEIGHT + 6;
    private static final int MARGIN = 20;
    private static final int COLUMN_WIDTH = 232;
    private static final int ROW_HEIGHT = 18;
    private static final int GROUP_HEADER = 16;
    private static final int GROUP_GAP = 8;
    private static final int BUTTON_BAR_HEIGHT = 32;
    private static final int LINE_HEIGHT = 10;

    private final Screen parent;
    private final List<OptionPage> pages = ActiniumOptionPages.build();
    private final List<ControlElement<?>> rows = new ArrayList<>();
    private final List<GroupHeader> headers = new ArrayList<>();
    private final List<TabButton> tabs = new ArrayList<>();
    private final List<ActiniumButton> buttons = new ArrayList<>();

    private int currentPage;
    private int scroll;
    private int contentHeight;
    private Option<?> hovered;

    private ActiniumButton applyButton;
    private ActiniumButton undoButton;

    private record GroupHeader(Component title, int y) {
    }

    public ActiniumOptionsScreen(Screen parent) {
        super(Component.translatable("actinium.options.title"));
        this.parent = parent;

        VulkanModOptionsStorage.INSTANCE.snapshot();
    }

    @Override
    protected void init() {
        rows.clear();
        headers.clear();
        tabs.clear();
        buttons.clear();

        int tabX = MARGIN;
        for (int i = 0; i < pages.size(); i++) {
            OptionPage page = pages.get(i);
            int tabWidth = font.width(page.title()) + 18;
            tabs.add(addWidget(new TabButton(tabX, TAB_Y, tabWidth, TAB_HEIGHT, page.title(), i)));
            tabX += tabWidth + 2;
        }

        int y = CONTENT_Y - scroll;
        for (OptionGroup group : pages.get(currentPage).groups()) {
            headers.add(new GroupHeader(group.title(), y));
            y += GROUP_HEADER;
            for (Option<?> option : group.options()) {
                ControlElement<?> row = createRow(option, MARGIN, y);
                rows.add(row);
                addWidget(row);
                y += ROW_HEIGHT;
            }
            y += GROUP_GAP;
        }
        contentHeight = y + scroll - CONTENT_Y - GROUP_GAP;

        int viewport = height - BUTTON_BAR_HEIGHT - CONTENT_Y;
        int maxScroll = Math.max(0, contentHeight - viewport);
        if (scroll > maxScroll) {
            scroll = maxScroll;
            rebuildRows();
            return;
        }

        int buttonY = height - BUTTON_BAR_HEIGHT + 6;
        int buttonWidth = 64;
        int gap = 6;
        int doneX = width - MARGIN - buttonWidth;
        int applyX = doneX - buttonWidth - gap;
        int undoX = applyX - buttonWidth - gap;

        addWidget(new ActiniumButton(MARGIN, buttonY, 96, 20,
                Component.translatable("actinium.options.shaderpacks"),
                ActiniumButton.Style.SECONDARY,
                () -> minecraft.setScreen(new ShaderPackScreen(this))));

        undoButton = addWidget(new ActiniumButton(undoX, buttonY, buttonWidth, 20,
                Component.translatable("actinium.options.undo"),
                ActiniumButton.Style.SECONDARY, this::undoChanges));
        applyButton = addWidget(new ActiniumButton(applyX, buttonY, buttonWidth, 20,
                Component.translatable("actinium.options.apply"),
                ActiniumButton.Style.SECONDARY, this::applyChanges));
        ActiniumButton doneButton = addWidget(new ActiniumButton(doneX, buttonY, buttonWidth, 20,
                CommonComponents.GUI_DONE,
                ActiniumButton.Style.PRIMARY, this::onDone));

        buttons.add(undoButton);
        buttons.add(applyButton);
        buttons.add(doneButton);

        updateButtonStates();
    }

    private void rebuildRows() {
        rebuildWidgets();
    }

    @SuppressWarnings("unchecked")
    private <T> ControlElement<T> createRow(Option<?> option, int x, int y) {
        Option<T> typed = (Option<T>) option;
        return typed.control().createElement(typed, x, y, COLUMN_WIDTH, ROW_HEIGHT);
    }

    private void updateButtonStates() {
        boolean dirty = pages.stream().flatMap(OptionPage::options).anyMatch(Option::isModified);
        applyButton.active = dirty;
        undoButton.active = dirty;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int panelTop = CONTENT_Y - 4;
        int panelBottom = height - BUTTON_BAR_HEIGHT;
        int panelLeft = MARGIN - 4;
        int panelRight = MARGIN + COLUMN_WIDTH + 4;

        drawPanel(guiGraphics, panelLeft, panelTop, COLUMN_WIDTH + 8, panelBottom - panelTop);

        guiGraphics.enableScissor(panelLeft + 1, panelTop + 1, panelRight - 1, panelBottom - 1);
        for (GroupHeader header : headers) {
            guiGraphics.drawString(font, header.title(), MARGIN + 3, header.y() + 4,
                    Theme.TEXT_HEADER, false);
        }
        for (ControlElement<?> row : rows) {
            row.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        guiGraphics.disableScissor();

        drawScrollbar(guiGraphics, panelRight, panelTop, panelBottom);

        for (TabButton tab : tabs) {
            tab.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        for (ActiniumButton button : buttons) {
            button.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        hovered = findHovered(mouseX, mouseY);
        renderDescription(guiGraphics, panelTop, panelBottom);

        updateButtonStates();
    }

    private void drawScrollbar(GuiGraphics guiGraphics, int panelRight, int top, int bottom) {
        int viewport = bottom - top;
        if (contentHeight <= viewport) {
            return;
        }
        int trackHeight = viewport - 4;
        int thumbHeight = Math.max(16, trackHeight * viewport / contentHeight);
        int travel = trackHeight - thumbHeight;
        int maxScroll = contentHeight - viewport;
        int thumbY = top + 2 + (maxScroll == 0 ? 0 : travel * scroll / maxScroll);

        guiGraphics.fill(panelRight - 4, top + 2, panelRight - 2, top + 2 + trackHeight,
                Theme.SLIDER_TRACK);
        guiGraphics.fill(panelRight - 4, thumbY, panelRight - 2, thumbY + thumbHeight,
                Theme.ACCENT);
    }

    private void drawPanel(GuiGraphics guiGraphics, int x, int y, int w, int h) {
        guiGraphics.fill(x, y, x + w, y + h, Theme.PANEL_BG);
        guiGraphics.renderOutline(x, y, w, h, Theme.PANEL_BORDER);
    }

    private Option<?> findHovered(int mouseX, int mouseY) {
        for (ControlElement<?> row : rows) {
            if (!isRowVisible(row)) {
                continue;
            }
            if (row.isMouseOver(mouseX, mouseY) || row.isFocused()) {
                return row.option();
            }
        }
        return null;
    }

    private void renderDescription(GuiGraphics guiGraphics, int top, int bottom) {
        int x = MARGIN + COLUMN_WIDTH + 12;
        int w = width - x - MARGIN;
        if (w < 120) {
            return;
        }
        drawPanel(guiGraphics, x - 4, top, w + 8, bottom - top);

        guiGraphics.enableScissor(x - 3, top + 1, x + w + 3, bottom - 1);
        try {
            if (hovered == null) {
                int y = top + 8;
                for (FormattedCharSequence line : font.split(
                        Component.translatable("actinium.options.description.idle"), w)) {
                    guiGraphics.drawString(font, line, x, y, Theme.TEXT_DIM, false);
                    y += LINE_HEIGHT;
                }
                return;
            }

            int y = top + 8;
            guiGraphics.drawString(font, hovered.name(), x, y, Theme.TEXT_HEADER, false);
            y += 14;

            for (FormattedCharSequence line : font.split(hovered.tooltip(), w)) {
                guiGraphics.drawString(font, line, x, y, Theme.TEXT, false);
                y += LINE_HEIGHT;
            }

            y += 6;
            guiGraphics.drawString(font, Component.translatable("actinium.options.impact",
                    hovered.impact().label()), x, y, Theme.TEXT_DIM, false);
            y += 14;

            if (!hovered.isAvailable()) {
                for (FormattedCharSequence line : font.split(hovered.unavailableReason(), w)) {
                    guiGraphics.drawString(font, line, x, y, Theme.WARNING, false);
                    y += LINE_HEIGHT;
                }
                y += 4;
            }

            for (OptionFlag flag : hovered.flags()) {
                Component note = Component.translatable(
                        "actinium.options.flag." + flag.name().toLowerCase());
                for (FormattedCharSequence line : font.split(note, w)) {
                    guiGraphics.drawString(font, line, x, y, Theme.WARNING, false);
                    y += LINE_HEIGHT;
                }
            }
        } finally {
            guiGraphics.disableScissor();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseY < CONTENT_Y - 4 || mouseY > height - BUTTON_BAR_HEIGHT) {
            for (TabButton tab : tabs) {
                if (tab.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
            }
            for (ActiniumButton b : buttons) {
                if (b.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
            }
            return false;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int viewport = height - BUTTON_BAR_HEIGHT - CONTENT_Y;
        if (mouseX < MARGIN + COLUMN_WIDTH + 4 && contentHeight > viewport) {
            int max = contentHeight - viewport;
            int next = Math.clamp(scroll - (int) (scrollY * 12), 0, max);
            if (next != scroll) {
                scroll = next;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void selectPage(int index) {
        if (index == currentPage) {
            return;
        }
        currentPage = index;
        scroll = 0;
        rebuildWidgets();
    }

    private boolean isRowVisible(ControlElement<?> row) {
        return row.getY() + row.getHeight() > CONTENT_Y - 4
                && row.getY() < height - BUTTON_BAR_HEIGHT;
    }

    private void applyChanges() {
        EnumSet<OptionFlag> flags = EnumSet.noneOf(OptionFlag.class);
        List<OptionStorage<?>> touched = new ArrayList<>();

        pages.stream().flatMap(OptionPage::options)
                .filter(Option::isModified)
                .forEach(option -> {
                    flags.addAll(option.flags());
                    option.applyChanges();
                    if (option instanceof OptionImpl<?, ?> impl && !touched.contains(impl.storage())) {
                        touched.add(impl.storage());
                    }
                });

        touched.forEach(OptionStorage::save);
        ActiniumSettingsApplier.apply(flags);
        updateButtonStates();
    }

    private void undoChanges() {
        pages.stream().flatMap(OptionPage::options).forEach(Option::reset);
        updateButtonStates();
    }

    private void onDone() {
        applyChanges();
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private final class TabButton extends AbstractWidget {
        private final int index;

        TabButton(int x, int y, int width, int height, Component title, int index) {
            super(x, y, width, height, title);
            this.index = index;
        }

        @Override
        protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            boolean active = index == currentPage;
            guiGraphics.fill(getX(), getY(), getX() + width, getY() + height,
                    active ? Theme.TAB_ACTIVE_BG : Theme.TAB_INACTIVE_BG);
            if (active) {
                guiGraphics.fill(getX(), getY() + height - 2, getX() + width, getY() + height, Theme.ACCENT);
            } else if (isHoveredOrFocused()) {
                guiGraphics.fill(getX(), getY() + height - 2, getX() + width, getY() + height, Theme.ACCENT_DIM);
            }
            int textX = getX() + (width - font.width(getMessage())) / 2;
            guiGraphics.drawString(font, getMessage(), textX, getY() + (height - 8) / 2,
                    active ? Theme.TEXT_HEADER : Theme.TEXT_DIM, false);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            selectPage(index);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
        }
    }
}
