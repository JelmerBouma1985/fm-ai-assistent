package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.update.UpdateCoordinator;
import com.github.fmaiassistent.update.UpdateStatus;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;

/** Shared dialog for automatic update offers and the gear menu. */
final class UpdatePanel extends Dialog {
    private final UpdateCoordinator coordinator;
    private final Span currentVersion = new Span();
    private final Span newVersion = new Span();
    private final Span status = new Span();
    private final ProgressBar progress = new ProgressBar();
    private final Button install;
    private final Button check;
    private final Button cancel;
    private final Anchor manual = new Anchor(
            "https://github.com/JelmerBouma1985/fm-ai-assistent/releases", "Download from GitHub Releases");
    private String offeredVersion;

    UpdatePanel(UpdateCoordinator coordinator) {
        this.coordinator = coordinator;
        setHeaderTitle("Updates");
        setWidth("420px");
        setMaxWidth("calc(100vw - 32px)");
        getElement().getThemeList().add("professional-dialog");
        check = new Button("Check for updates", e -> {
            coordinator.check(true);
            render();
        });
        install = new Button("Download & install", e -> {
            coordinator.install();
            render();
        });
        install.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        cancel = new Button("Cancel download", e -> coordinator.cancel());
        status.getStyle().set("overflow-wrap", "anywhere");
        VerticalLayout contents = new VerticalLayout(currentVersion, newVersion, status, progress, manual);
        contents.setPadding(false);
        if (coordinator.local()) contents.addComponentAsFirst(new Span("Local development update source"));
        add(contents);
        getFooter().add(check, cancel, install, new Button("Close", e -> close()));
        render();
    }

    @Override
    public void open() {
        var state = coordinator.status();
        if (state != null && state.phase() == UpdateStatus.Phase.AVAILABLE && state.release() != null) {
            offeredVersion = state.release().version();
        } else {
            coordinator.check(true);
        }
        render();
        super.open();
    }

    void refresh() {
        render();
        var state = coordinator.status();
        if (state != null && state.phase() == UpdateStatus.Phase.AVAILABLE && state.release() != null
                && !state.release().version().equals(offeredVersion)) {
            offeredVersion = state.release().version();
            super.open();
        }
    }

    private void render() {
        var state = coordinator.status();
        if (state == null) return;
        currentVersion.setText("Current version: " + state.currentVersion());
        newVersion.setVisible(state.release() != null);
        newVersion.setText(state.release() == null ? "" : "New version: " + state.release().version());
        status.setText(state.message());
        check.setEnabled(!state.busy());
        boolean available = state.phase() == UpdateStatus.Phase.AVAILABLE;
        install.setVisible(available);
        install.setEnabled(available);
        cancel.setVisible(state.phase() == UpdateStatus.Phase.DOWNLOADING
                || state.phase() == UpdateStatus.Phase.PREPARING);
        progress.setVisible(state.busy());
        progress.setIndeterminate(state.busy() && state.totalBytes() == 0);
        progress.setValue(state.totalBytes() == 0 ? 0 : Math.min(1, (double) state.bytesDone() / state.totalBytes()));
        manual.setVisible(state.phase() == UpdateStatus.Phase.UNSUPPORTED || state.phase() == UpdateStatus.Phase.ERROR);
    }
}
