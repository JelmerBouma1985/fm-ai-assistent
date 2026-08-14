package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.tactic.TacticContext;
import com.github.fmaiassistent.tactic.TacticContextService;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.server.streams.UploadHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

final class TacticContextPanel extends Details {
    private static final int MAX_UPLOAD_BYTES = 20 * 1024 * 1024;

    private final TacticContextService contexts;
    private final Button clear = new Button("Clear");
    private final Span status = new Span();
    private final Span details = new Span();
    private final Pre preview = new Pre();
    private final Details previewDetails = new Details("Preview AI context", preview);
    private final Upload upload;
    private final Map<String, byte[]> pendingUploads = new LinkedHashMap<>();

    TacticContextPanel(TacticContextService contexts) {
        this.contexts = contexts;
        addClassName("tactic-context-panel");
        setOpened(true);

        clear.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        clear.addClickListener(ignored -> {
            contexts.clear();
            refresh();
        });

        upload = new Upload(UploadHandler.inMemory((metadata, bytes) -> {
            String name = metadata.fileName() == null ? "uploaded-tactic" : metadata.fileName();
            if (bytes.length > MAX_UPLOAD_BYTES) {
                throw new IllegalArgumentException("Tactic upload is larger than 20 MB");
            }
            synchronized (pendingUploads) {
                pendingUploads.put(name, bytes);
            }
        }));
        Button uploadButton = new Button("Choose tactic file (.fmf)", VaadinIcon.UPLOAD.create());
        uploadButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        upload.setUploadButton(uploadButton);
        upload.setDropLabel(new Span("or drop your .fmf tactic file here"));
        upload.setAcceptedFileTypes(".fmf");
        upload.setMaxFileSize(MAX_UPLOAD_BYTES);
        upload.setMaxFiles(1);
        upload.setDropAllowed(true);
        upload.setWidthFull();
        upload.addClassName("tactic-context-upload");
        upload.addAllFinishedListener(ignored -> importUploads());
        upload.addFileRejectedListener(event -> Notification.show(event.getErrorMessage()));

        Span heading = new Span("Let the AI understand your current tactic");
        heading.addClassName("tactic-context-heading");
        Span help = new Span(
                "Upload one Football Manager 2026 .fmf tactic file. Its roles, duties, mentality "
                        + "and tactical style will be added automatically to Codex, Antigravity and GitHub Copilot chats.");
        help.addClassName("tactic-context-help");
        status.addClassName("tactic-context-status");
        details.addClassName("tactic-context-details");
        preview.addClassName("tactic-context-preview");
        previewDetails.addClassName("tactic-context-preview-details");

        HorizontalLayout controls = new HorizontalLayout(status, clear);
        controls.setAlignItems(HorizontalLayout.Alignment.CENTER);
        controls.expand(status);
        controls.setWidthFull();
        controls.addClassName("tactic-context-controls");

        VerticalLayout body = new VerticalLayout(heading, help, upload, controls, details, previewDetails);
        body.setPadding(false);
        body.setSpacing(true);
        body.addClassName("tactic-context-body");
        add(body);
        refresh();
    }

    private void importUploads() {
        Map<String, byte[]> files;
        synchronized (pendingUploads) {
            files = new LinkedHashMap<>(pendingUploads);
            pendingUploads.clear();
        }
        upload.clearFileList();
        if (!files.isEmpty()) {
            runImport(() -> contexts.loadUploads(files));
        }
    }

    private void runImport(Supplier<TacticContext> operation) {
        UI ui = getUI().orElse(null);
        if (ui == null) {
            return;
        }
        setBusy(true);
        Thread.ofVirtual().name("tactic-context-import").start(() -> {
            try {
                TacticContext context = operation.get();
                if (ui.isAttached()) {
                    ui.access(() -> {
                        setBusy(false);
                        refresh(context);
                    });
                }
            } catch (RuntimeException exception) {
                if (ui.isAttached()) {
                    ui.access(() -> {
                        setBusy(false);
                        Notification.show(safeMessage(exception));
                        refresh();
                    });
                }
            }
        });
    }

    private void setBusy(boolean busy) {
        upload.setEnabled(!busy);
        clear.setEnabled(!busy);
        if (busy) {
            status.setText("Reading tactic…");
        }
    }

    private void refresh() {
        refresh(contexts.current());
    }

    private void refresh(TacticContext context) {
        if (!context.active()) {
            setSummaryText("AI tactic context · upload a .fmf file");
            status.setText("No tactic uploaded yet");
            details.setText("");
            preview.setText("");
            previewDetails.setVisible(false);
            clear.setVisible(false);
            return;
        }
        setSummaryText("AI tactic context · " + context.title());
        status.setText("Active for Codex, Antigravity and GitHub Copilot");
        String imported = "Files: " + String.join(", ", context.importedFiles());
        if (!context.warnings().isEmpty()) {
            imported += "\nNotes: " + String.join(" · ", context.warnings());
        }
        details.setText(imported);
        preview.setText(context.markdown());
        previewDetails.setVisible(true);
        clear.setVisible(true);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? "Could not import tactic" : message;
    }
}
