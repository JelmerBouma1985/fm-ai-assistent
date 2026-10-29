package com.github.fmaiassistent.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One canonical club identity, independent of its competition/gender catalogue views. */
@Entity
@Table(name = "club_records")
public class NativeClubEntity {
    @Id @Column(name = "unique_id") private Long uniqueId;
    @Column(name = "record_index") private Integer recordIndex;
    @Column(length = 32) private String kind;
    @Column(length = 1024) private String name;
    @Column(name = "source_address") private Long sourceAddress;
    @Column(name = "prefix_hex", length = 32) private String prefixHex;
    @Column(name = "module_sha256", length = 64) private String moduleSha256;
    @Column(name = "uefa_coefficient_hex", length = 152) private String uefaCoefficientHex;
    @Column(name = "uefa_rule_version") private Integer uefaRuleVersion;
    @Column(name = "financial_loans_hex", length = 28672) private String financialLoansHex;
    @Column(name = "financial_loan_clock") private Integer financialLoanClock;
    @Column(name = "financial_ledger_hex", length = 2320) private String financialLedgerHex;
    @Column(name = "financial_ledger_clock") private Integer financialLedgerClock;
    @Column(name = "financial_competitions_hex", length = 122960) private String financialCompetitionsHex;
    @Column(name = "financial_history_hex", length = 33428) private String financialHistoryHex;

    protected NativeClubEntity() { }
    public Long getUniqueId() { return uniqueId; }
    public String getName() { return name; }
    public String getModuleSha256() { return moduleSha256; }
    public String getUefaCoefficientHex() { return uefaCoefficientHex; }
    public Integer getUefaRuleVersion() { return uefaRuleVersion; }
    public String getFinancialLoansHex() { return financialLoansHex; }
    public Integer getFinancialLoanClock() { return financialLoanClock; }
    public String getFinancialLedgerHex() { return financialLedgerHex; }
    public Integer getFinancialLedgerClock() { return financialLedgerClock; }
    public String getFinancialCompetitionsHex() { return financialCompetitionsHex; }
    public String getFinancialHistoryHex() { return financialHistoryHex; }
}
