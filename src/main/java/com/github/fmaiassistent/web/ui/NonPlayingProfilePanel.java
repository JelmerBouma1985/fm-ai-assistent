package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.memory.NonPlayingProfileLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

/** Native storage values keep their20/100scale; unknown bytes remain preserved in the snapshot. */
final class NonPlayingProfilePanel extends VerticalLayout {
    static boolean hasData(String hex,String sha256) { return !NonPlayingProfileLayout.values(hex,sha256).isEmpty(); }

    NonPlayingProfilePanel(String hex,String sha256) {
        setPadding(false);setWidthFull();
        var values=NonPlayingProfileLayout.values(hex,sha256);
        if(values.isEmpty()){add(new Span("No management profile in this snapshot."));return;}
        Grid<NonPlayingProfileLayout.AttributeValue> grid=new Grid<>();
        grid.addColumn(NonPlayingProfileLayout.AttributeValue::name).setHeader("Native attribute").setFlexGrow(1);
        grid.addColumn(NonPlayingProfileLayout.AttributeValue::nativeValue).setHeader("Value").setWidth("100px").setFlexGrow(0);
        grid.addColumn(v->"/ "+v.scale()).setHeader("Scale").setWidth("100px").setFlexGrow(0);
        grid.setItems(values);grid.setHeight("min(620px, 60vh)");grid.setWidthFull();
        grid.addThemeVariants(GridVariant.LUMO_NO_BORDER,GridVariant.LUMO_ROW_STRIPES);
        add(grid);
    }
}
