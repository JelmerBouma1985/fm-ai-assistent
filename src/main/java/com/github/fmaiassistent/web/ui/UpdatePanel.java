package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.update.*;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.shared.Registration;

/** Poll on the UI; background update code never accesses Vaadin components. */
final class UpdatePanel extends VerticalLayout {
    private final UpdateCoordinator coordinator;
    private final Span status = new Span();
    private final ProgressBar progress = new ProgressBar();
    private final Dialog dialog = new Dialog();
    private final Span details = new Span();
    private final Button install, later, check, retry;
    private Registration poll;
    private String offered;
    UpdatePanel(UpdateCoordinator coordinator) {
        this.coordinator = coordinator;
        setPadding(false); setSpacing(false);
        check = new Button("Check for updates", e -> { offered = null; coordinator.check(true); });
        install = new Button("Download & install", e -> coordinator.install());
        later = new Button("Later", e -> { coordinator.later(); dialog.close(); });
        retry = new Button("Check again", e -> { offered = null; coordinator.check(true); });
        var cancel = new Button("Cancel download", e -> coordinator.cancel());
        dialog.setHeaderTitle("Desktop update"); dialog.setCloseOnEsc(false); dialog.setCloseOnOutsideClick(false);
        details.getStyle().set("white-space", "pre-wrap");
        var contents = new VerticalLayout(details, progress, install, later, cancel, retry);
        if (coordinator.local()) contents.addComponentAsFirst(new Span("LOCAL UPDATE SOURCE / DEVELOPMENT MODE"));
        dialog.add(contents);
        dialog.getFooter().add(new Button("Close", e -> dialog.close()));
        add(check, status, new Anchor("https://github.com/JelmerBouma1985/fm-ai-assistent/releases", "Manual updates: GitHub Releases"));
        addAttachListener(event -> {
            var ui = event.getUI();
            ui.setPollInterval(1000);
            poll = ui.addPollListener(e -> render());
            render();
        });
        addDetachListener(event -> { if (poll != null) poll.remove(); });
    }
    private void render() {
        var state = coordinator.status();
        if (state == null) return;
        status.setText((coordinator.local() ? "LOCAL DEV MODE: " : "") + state.currentVersion() + " - " + state.phase() + ": " + state.message());
        check.setEnabled(!state.busy());
        retry.setEnabled(!state.busy());
        install.setEnabled(state.phase() == UpdateStatus.Phase.AVAILABLE);
        later.setEnabled(state.phase() == UpdateStatus.Phase.AVAILABLE);
        progress.setIndeterminate(state.busy() && state.totalBytes() == 0);
        progress.setValue(state.totalBytes() == 0 ? 0 : Math.min(1, (double) state.bytesDone() / state.totalBytes()));
        String text = "Current: " + state.currentVersion() + "\n" + state.phase() + ": " + state.message();
        if (state.totalBytes() > 0) text += " (" + (100 * state.bytesDone() / state.totalBytes()) + "%)";
        if (state.release() != null) text += "\nNew: " + state.release().version() + "\n" + state.release().releaseNotes();
        details.setText(text);
        if (state.phase() == UpdateStatus.Phase.AVAILABLE && !state.release().version().equals(offered)) {
            offered = state.release().version(); dialog.open();
        }
    }
}
