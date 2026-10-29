package com.github.fmaiassistent.domain.entity;
import jakarta.persistence.*;

/** UID-keyed references consumed by financial records, including competitions absent from the catalogue. */
@Entity @Table(name="competition_name_records")
public class CompetitionNameEntity {
    @Id @Column(name="unique_id") private Long uniqueId;
    @Column(name="record_index") private Integer recordIndex;
    @Column(name="source_address") private Long sourceAddress;
    @Column(name="prefix_hex",length=32) private String prefixHex;
    @Column(name="module_sha256",length=64) private String moduleSha256;
    @Column(name="name_hex",length=2056) private String nameHex;
    @Column(name="short_name_hex",length=2056) private String shortNameHex;
    @Column(length=1024) private String name;
    @Column(name="short_name",length=1024) private String shortName;
    protected CompetitionNameEntity() { }
    public Long getUniqueId(){return uniqueId;}
    public Integer getRecordIndex(){return recordIndex;}
    public String getPrefixHex(){return prefixHex;}
    public String getModuleSha256(){return moduleSha256;}
    public String getNameHex(){return nameHex;}
    public String getShortNameHex(){return shortNameHex;}
    public String getName(){return name;}
    public String getShortName(){return shortName;}
}
