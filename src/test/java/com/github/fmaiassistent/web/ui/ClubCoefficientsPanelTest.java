package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.memory.ClubUefaCoefficientLayout;
import com.vaadin.flow.component.Component;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ClubCoefficientsPanelTest {
    @Test void showsCoefficientsRankingAndCurrentPointsWithoutGuessingCacheOrUnassignedFlags() {
        var values=new ClubUefaCoefficientLayout.Values(List.of(new BigDecimal("14.750"),new BigDecimal("27.250")),
                16,null,new BigDecimal("116.500"),new BigDecimal("169.250"),new BigDecimal("37.00"),116.5f);
        var panel=new ClubCoefficientsPanel(values);var text=text(panel);
        assertThat(text).contains("Five-year coefficient","116.5","Ten-year coefficient","169.25",
                "Five-year ranking","16","Ten-year ranking","Unavailable","Current-season points","37","14.75","27.25");
        assertThat(text).doesNotContain("dRwB","cache","0x","Hex");
    }
    private static String text(Component c) {return c.getElement().getText()+c.getChildren().map(ClubCoefficientsPanelTest::text).reduce("",(a,b)->a+" "+b);}
}
