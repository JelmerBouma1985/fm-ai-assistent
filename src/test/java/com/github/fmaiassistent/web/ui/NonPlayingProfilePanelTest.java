package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.memory.NonPlayingProfileLayout;
import com.vaadin.flow.component.grid.Grid;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NonPlayingProfilePanelTest {
    @Test void bothEntityDialogsExposeTheOptionalProfileThroughItsOwnTab()throws Exception {
        var ui=new com.vaadin.flow.component.UI();
        ui.getInternals().setSession(org.mockito.Mockito.mock(com.vaadin.flow.server.VaadinSession.class));
        com.vaadin.flow.component.UI.setCurrent(ui);
        try {
            var view=MainViewTest.view(org.mockito.Mockito.mock(com.github.fmaiassistent.repository.ContractRepository.class));
            var data=java.util.Map.<String,Object>of("unique_id",222138L,"non_playing_profile_hex","05".repeat(52),
                    "non_playing_profile_sha256",NonPlayingProfileLayout.MODULE_SHA256);
            for(var entity:java.util.List.of(com.github.fmaiassistent.domain.entity.PlayerEntity.fromExportRow(data),
                    com.github.fmaiassistent.domain.entity.StaffEntity.fromExportRow(data))) {
                var open=MainView.class.getDeclaredMethod(entity instanceof com.github.fmaiassistent.domain.entity.PlayerEntity?
                        "openPlayerDetailsDialog":"openStaffDetailsDialog",entity.getClass());open.setAccessible(true);open.invoke(view,entity);
                ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
                var dialog=descendants(ui).filter(com.vaadin.flow.component.dialog.Dialog.class::isInstance)
                        .map(com.vaadin.flow.component.dialog.Dialog.class::cast).filter(com.vaadin.flow.component.dialog.Dialog::isOpened).findFirst().orElseThrow();
                var tabs=descendants(dialog).filter(com.vaadin.flow.component.tabs.Tabs.class::isInstance).map(com.vaadin.flow.component.tabs.Tabs.class::cast).findFirst().orElseThrow();
                var tab=tabs.getChildren().filter(com.vaadin.flow.component.tabs.Tab.class::isInstance).map(com.vaadin.flow.component.tabs.Tab.class::cast)
                        .filter(t->t.getLabel().equals("Management profile")).findFirst().orElseThrow();tabs.setSelectedTab(tab);
                var grid=descendants(dialog).filter(Grid.class::isInstance).map(Grid.class::cast).findFirst().orElseThrow();
                assertThat(grid.getGenericDataView().getItems().count()).isEqualTo(50);dialog.close();
            }
        }finally{com.vaadin.flow.component.UI.setCurrent(null);}
    }
    @Test void nativeValuesRetainSignednessAndStorageScaleInTheScreen() {
        byte[] raw=new byte[52];raw[2]=17;raw[48]=85;raw[45]=(byte)0xff;raw[46]=25;
        var panel=new NonPlayingProfilePanel(java.util.HexFormat.of().formatHex(raw),NonPlayingProfileLayout.MODULE_SHA256);
        var grid=(Grid<?>)panel.getChildren().findFirst().orElseThrow();
        var values=grid.getGenericDataView().getItems().map(v->(NonPlayingProfileLayout.AttributeValue)v).toList();
        assertThat(values).hasSize(50);
        assertThat(values).anySatisfy(v->{assertThat(v.name()).isEqualTo("Eccentricity");assertThat(v.nativeValue()).isEqualTo(85);assertThat(v.scale()).isEqualTo(100);});
        assertThat(values).anySatisfy(v->{assertThat(v.name()).isEqualTo("Coaching Technique");assertThat(v.nativeValue()).isEqualTo(17);assertThat(v.scale()).isEqualTo(20);});
        assertThat(NonPlayingProfilePanel.hasData(null,null)).isFalse();
    }
    private static java.util.stream.Stream<com.vaadin.flow.component.Component> descendants(com.vaadin.flow.component.Component c){return java.util.stream.Stream.concat(java.util.stream.Stream.of(c),c.getChildren().flatMap(NonPlayingProfilePanelTest::descendants));}
}
