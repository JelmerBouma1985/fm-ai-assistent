package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.memory.ClubFinancialLoanLayout;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Gross individual loans; no guessed net-debt aggregate. All rows retain their native order. */
public class ClubFinancialLoansPanel extends VerticalLayout {
    public ClubFinancialLoansPanel(ClubFinancialLoanLayout.Profile profile) {
        setPadding(false);add(new H4("Club debts and loans"));
        add(new Span("As of "+date(profile.capturedDate())));
        if(profile.loans().isEmpty()){add(new Span("No financial loans recorded."));return;}
        for(var loan:profile.loans()) {
            add(new H4(loan.typeName()));var fields=new FormLayout();
            item(fields,"Original amount",money(loan.originalPrincipal()));
            item(fields,"Repayments start",date(loan.repaymentsStart()));item(fields,"Until",date(loan.maturity()));
            item(fields,loan.kind()==13?"Payment at maturity":loan.kind()<=16?"Payable (per month)":"Payable",money(loan.payableAmount()));
            if(loan.interestOnly())item(fields,"Scheduled payment amount",money(loan.scheduledPayment()));
            item(fields,"Interest component",money(loan.interestComponent()));
            item(fields,"Remaining principal",money(loan.remainingPrincipal()));
            item(fields,"Remaining interest",money(loan.remainingInterest()));
            if(loan.paymentMode()!=0)item(fields,"Payment terms",loan.interestOnly()?"Interest-only payment":"Unavailable");
            if((loan.kind()==10||loan.kind()==14)&&loan.promotionRepayment()!=0)
                item(fields,"Immediate repayment on promotion",money(loan.promotionRepayment()));
            add(fields);
        }
    }
    private static void item(FormLayout f,String label,String value){f.addFormItem(new Span(value),label);}
    private static String date(LocalDate d){return d==null?"Unavailable":d.format(DateTimeFormatter.ofPattern("dd/MM/uuuu"));}
    private static String money(Integer value){return value==null?"Unavailable":"£"+NumberFormat.getIntegerInstance(Locale.UK).format(value);}
}
