package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.update.*;
import com.vaadin.flow.component.button.Button;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UpdatePanelTest {
    private com.vaadin.flow.component.UI ui;
    @org.junit.jupiter.api.BeforeEach void uiContext() { ui = new com.vaadin.flow.component.UI(); com.vaadin.flow.component.UI.setCurrent(ui); }
    @org.junit.jupiter.api.AfterEach void clearUiContext() { com.vaadin.flow.component.UI.setCurrent(null); }
    @Test void errorDialogCanBeClosedAndRetried() {
        var coordinator = mock(UpdateCoordinator.class);
        when(coordinator.status()).thenReturn(new UpdateStatus(UpdateStatus.Phase.ERROR, "Checksum mismatch", "2.1.0", null, 0, 0));
        var panel = new UpdatePanel(coordinator);
        panel.open();
        assertTrue(panel.isOpened());
        verify(coordinator).check(true);

        footerButton(panel, "Close").click();
        assertFalse(panel.isOpened());

        panel.open();
        assertTrue(panel.isOpened());
        verify(coordinator, times(2)).check(true);

        Button check = footerButton(panel, "Check for updates");
        assertTrue(check.isEnabled());
        check.click();
        verify(coordinator, times(3)).check(true);
    }

    private static Button footerButton(UpdatePanel panel, String text) {
        return panel.getFooter().getElement().getChildren()
                .flatMap(element -> element.getComponent().stream())
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
    }
}
