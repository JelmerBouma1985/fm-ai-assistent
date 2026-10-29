package com.github.fmaiassistent.web.ui;
import com.github.fmaiassistent.memory.ClubFinancialLoanLayout;
import com.vaadin.flow.component.Component;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ClubFinancialLoansPanelTest {
    @Test void capturedBankLoanShowsGrossPrincipalInterestAndDatesWithoutNetDebtGuess() {
        var profile=ClubFinancialLoanLayout.values("a6cfa205d6b80500a4e90000000000008a3207089032ee0701000000",0x07F41A8D,ClubFinancialLoanLayout.MODULE_SHA256).orElseThrow();
        assertThat(text(new ClubFinancialLoansPanel(profile))).contains("Bank loan","£94,556,070","£374,998","£72,807,966","£13,816,572","24/05/2030","18/05/2055").doesNotContain("Net debt","0x","Hex");
    }
    private static String text(Component c){return c.getElement().getText()+c.getChildren().map(ClubFinancialLoansPanelTest::text).reduce("",(a,b)->a+" "+b);}
}
