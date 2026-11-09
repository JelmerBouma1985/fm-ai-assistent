package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.update.*;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UpdatePanelTest {
    private com.vaadin.flow.component.UI ui;
    @org.junit.jupiter.api.BeforeEach void uiContext() { ui = new com.vaadin.flow.component.UI(); com.vaadin.flow.component.UI.setCurrent(ui); }
    @org.junit.jupiter.api.AfterEach void clearUiContext() { com.vaadin.flow.component.UI.setCurrent(null); }
    @Test void errorDialogCanBeClosedAndRetried() throws Exception {
        var coordinator = mock(UpdateCoordinator.class);
        when(coordinator.status()).thenReturn(new UpdateStatus(UpdateStatus.Phase.ERROR, "Checksum mismatch", "2.1.0", null, 0, 0));
        var panel = new UpdatePanel(coordinator);
        var render = UpdatePanel.class.getDeclaredMethod("render"); render.setAccessible(true); render.invoke(panel);
        var field = UpdatePanel.class.getDeclaredField("dialog"); field.setAccessible(true);
        Dialog dialog = (Dialog) field.get(panel); dialog.open();
        Button close = (Button) dialog.getFooter().getElement().getChild(0).getComponent().orElseThrow();
        close.click(); assertFalse(dialog.isOpened());
        var retry = UpdatePanel.class.getDeclaredField("retry"); retry.setAccessible(true);
        ((Button) retry.get(panel)).click(); verify(coordinator).check(true);
    }
}
