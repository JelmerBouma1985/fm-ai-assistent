package com.github.fmaiassistent.web.ui;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Read-only catalogue coverage investigation, explicitly opted in; never refreshes native state. */
@Tag("fm26-live")
class CompetitionFinancialNameCoverageLiveTest {
 @Test @EnabledIfSystemProperty(named="fm26.financial.competition.names.database",matches="true")
 void compareAll60IndependentNativeNamesWithExistingCatalogueWithoutDroppingMissingKeys()throws Exception {
  String url=System.getProperty("fm26.delivery.database.url");assertThat(url).contains("ACCESS_MODE_DATA=r","IFEXISTS=TRUE");
  var jdbc=new JdbcTemplate(new DriverManagerDataSource(url,"sa",""));assertThat(jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='fm_pid'",String.class)).isEqualTo("22268");
  assertThat(jdbc.queryForObject("SELECT meta_value FROM load_metadata WHERE meta_key='snapshot_id'",String.class)).isEqualTo("26842f9b-c476-4f95-8678-b019a150866d");
  byte[] input;try(var in=Files.newInputStream(Path.of("docs/reverse-engineering/fm26/evidence/financial_competition_names_verified_20261027.json"))){input=in.readNBytes(1048577);}assertThat(input.length).isLessThanOrEqualTo(1048576);
  var nativeRows=new tools.jackson.databind.json.JsonMapper().readTree(input).path("rows");assertThat(nativeRows.size()).isEqualTo(60);
  var catalogue=new HashMap<Long,Map<String,Object>>();for(var row:jdbc.queryForList("SELECT id,source_address,name,nation,reputation FROM competitions")) {
   if(row.get("SOURCE_ADDRESS") instanceof Number address)assertThat(catalogue.put(address.longValue(),row)).isNull();
  }
  int present=0,matching=0;var rows=new ArrayList<Map<String,Object>>();
  for(var nativeRow:nativeRows){long address=Long.parseUnsignedLong(nativeRow.path("address").asText(),16),uid=nativeRow.path("uid").asLong();
   var row=new LinkedHashMap<String,Object>();row.put("uid",uid);row.put("address",nativeRow.path("address").asText());row.put("nativeLongName",nativeRow.path("longName").isNull()?null:nativeRow.path("longName").asText());row.put("nativeShortName",nativeRow.path("shortName").isNull()?null:nativeRow.path("shortName").asText());
   var stored=catalogue.get(address);row.put("cataloguePresent",stored!=null);if(stored!=null){present++;String name=(String)stored.get("NAME");row.put("catalogueName",name);row.put("nation",stored.get("NATION"));row.put("reputation",stored.get("REPUTATION"));
    boolean equals=name!=null&&(name.equals(row.get("nativeLongName"))||name.equals(row.get("nativeShortName")));row.put("nameMatchesNativeVariant",equals);if(equals)matching++;}
   rows.add(row);
  }
  assertThat(matching).isEqualTo(present);
  var report=Map.of("status","OBSERVED","pid",22268,"snapshot","26842f9b-c476-4f95-8678-b019a150866d","catalogueRecords",catalogue.size(),"financialKeys",60,"present",present,"missing",60-present,"matchingNativeNames",matching,"rows",rows);
  String dest=System.getProperty("fm26.financial.competition.names.database.report");assertThat(dest).isNotBlank();Files.writeString(Path.of(dest),new tools.jackson.databind.json.JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report),StandardOpenOption.CREATE_NEW);
 }
}
