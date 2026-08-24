package com.github.fmaiassistent.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.LinkedHashMap;
import java.util.Map;

import com.github.fmaiassistent.staff.StaffRoleRatingCalculator;

@Entity
@Table(name = "staff")
public class StaffEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "staff_index") private Integer staffIndex;
    @Column(name = "record_address", length = 32) private String recordAddress;
    @Column(name = "unique_id") private Long uniqueId;
    @Column(length = 1024) private String name;
    @Column(length = 16) private String gender;
    @Column(length = 1024) private String nationality;
    @Column(length = 1024) private String club;
    @ManyToOne
    @JoinColumn(name = "club_id", foreignKey = @ForeignKey(name = "fk_staff_club"))
    private ClubEntity clubEntity;
    @Column(length = 1024) private String division;
    @Column(name = "job_id") private Integer jobId;
    @Column(length = 128) private String job;
    @Column(name = "date_of_birth", length = 32) private String dateOfBirth;
    private Integer age;
    @Column(name = "age_as_of", length = 32) private String ageAsOf;
    @Column(name = "salary_weekly_raw") private Long salaryWeeklyRaw;
    @Column(name = "contract_end_date", length = 32) private String contractEndDate;
    @Column(name = "home_reputation") private Integer homeReputation;
    @Column(name = "current_reputation") private Integer currentReputation;
    @Column(name = "world_reputation") private Integer worldReputation;
    private Integer ca;
    private Integer pa;
    private Integer authority;
    private Integer attacking;
    private Integer defending;
    private Integer fitness;
    private Integer goalkeeping;
    private Integer possession;
    private Integer technical;
    private Integer tactical;
    @Column(name = "set_pieces") private Integer setPieces;
    private Integer determination;
    @Column(name = "people_management") private Integer peopleManagement;
    private Integer motivating;
    @Column(name = "judging_player_ability") private Integer judgingPlayerAbility;
    @Column(name = "judging_player_potential") private Integer judgingPlayerPotential;
    @Column(name = "judging_staff_ability") private Integer judgingStaffAbility;
    private Integer negotiating;
    @Column(name = "tactical_knowledge") private Integer tacticalKnowledge;
    private Integer physiotherapy;
    @Column(name = "sports_science") private Integer sportsScience;
    @Column(name = "data_analysis") private Integer dataAnalysis;
    @Column(name = "working_with_youngsters") private Integer workingWithYoungsters;

    protected StaffEntity() {
    }

    public static StaffEntity fromExportRow(Map<String, Object> row) {
        StaffEntity staff = new StaffEntity();
        staff.staffIndex = integer(row, "staff_index");
        staff.recordAddress = text(row, "record_address");
        staff.uniqueId = longValue(row, "unique_id");
        staff.name = text(row, "name");
        staff.gender = text(row, "gender");
        staff.nationality = text(row, "nationality");
        staff.club = text(row, "club");
        staff.division = text(row, "division");
        staff.jobId = integer(row, "job_id");
        staff.job = text(row, "job");
        staff.dateOfBirth = text(row, "date_of_birth");
        staff.age = integer(row, "age");
        staff.ageAsOf = text(row, "age_as_of");
        staff.salaryWeeklyRaw = longValue(row, "salary_weekly_raw");
        staff.contractEndDate = text(row, "contract_end_date");
        staff.homeReputation = integer(row, "home_reputation");
        staff.currentReputation = integer(row, "current_reputation");
        staff.worldReputation = integer(row, "world_reputation");
        staff.ca = integer(row, "ca");
        staff.pa = integer(row, "pa");
        staff.authority = integer(row, "authority");
        staff.attacking = integer(row, "attacking");
        staff.defending = integer(row, "defending");
        staff.fitness = integer(row, "fitness");
        staff.goalkeeping = integer(row, "goalkeeping");
        staff.possession = integer(row, "possession");
        staff.technical = integer(row, "technical");
        staff.tactical = integer(row, "tactical");
        staff.setPieces = integer(row, "set_pieces");
        staff.determination = integer(row, "determination");
        staff.peopleManagement = integer(row, "people_management");
        staff.motivating = integer(row, "motivating");
        staff.judgingPlayerAbility = integer(row, "judging_player_ability");
        staff.judgingPlayerPotential = integer(row, "judging_player_potential");
        staff.judgingStaffAbility = integer(row, "judging_staff_ability");
        staff.negotiating = integer(row, "negotiating");
        staff.tacticalKnowledge = integer(row, "tactical_knowledge");
        staff.physiotherapy = integer(row, "physiotherapy");
        staff.sportsScience = integer(row, "sports_science");
        staff.dataAnalysis = integer(row, "data_analysis");
        staff.workingWithYoungsters = integer(row, "working_with_youngsters");
        return staff;
    }

    public Map<String, Object> toApiMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("staff_unique_id", uniqueId);
        out.put("name", name);
        out.put("gender", gender);
        out.put("nationality", nationality);
        out.put("club", club);
        out.put("division", division);
        out.put("job_id", jobId);
        out.put("job", job);
        out.put("date_of_birth", dateOfBirth);
        out.put("age", age);
        out.put("salary_weekly", salaryWeeklyRaw);
        out.put("contract_end_date", contractEndDate);
        out.put("home_reputation", homeReputation);
        out.put("current_reputation", currentReputation);
        out.put("world_reputation", worldReputation);
        out.put("ca", ca);
        out.put("pa", pa);
        attributes().forEach(out::put);
        out.put("best_coaching_role", StaffRoleRatingCalculator.bestRating(this)
                .map(StaffRoleRatingCalculator.RoleRating::toApiMap).orElse(null));
        out.put("role_ratings", StaffRoleRatingCalculator.ratings(this).stream()
                .map(StaffRoleRatingCalculator.RoleRating::toApiMap).toList());
        return out;
    }

    public Map<String, Integer> attributes() {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("authority", authority); out.put("attacking", attacking); out.put("defending", defending);
        out.put("fitness", fitness); out.put("goalkeeping", goalkeeping);
        out.put("possession", possession); out.put("technical", technical); out.put("tactical", tactical);
        out.put("set_pieces", setPieces); out.put("determination", determination);
        out.put("people_management", peopleManagement); out.put("motivating", motivating);
        out.put("judging_player_ability", judgingPlayerAbility); out.put("judging_player_potential", judgingPlayerPotential);
        out.put("judging_staff_ability", judgingStaffAbility); out.put("negotiating", negotiating);
        out.put("tactical_knowledge", tacticalKnowledge); out.put("physiotherapy", physiotherapy);
        out.put("sports_science", sportsScience); out.put("data_analysis", dataAnalysis);
        out.put("working_with_youngsters", workingWithYoungsters);
        return out;
    }

    public Object value(String key) {
        if (key == null) return null;
        return switch (key.toLowerCase()) {
            case "staff_unique_id", "unique_id" -> uniqueId;
            case "name" -> name; case "gender" -> gender; case "nationality" -> nationality;
            case "club" -> club; case "division" -> division; case "job" -> job; case "job_id" -> jobId;
            case "age" -> age; case "date_of_birth" -> dateOfBirth; case "salary_weekly", "salary_weekly_raw" -> salaryWeeklyRaw;
            case "contract_end_date" -> contractEndDate; case "home_reputation" -> homeReputation;
            case "current_reputation" -> currentReputation; case "world_reputation" -> worldReputation;
            case "ca" -> ca; case "pa" -> pa;
            default -> attributes().get(key.toLowerCase());
        };
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key); return value == null ? "" : String.valueOf(value);
    }
    private static Integer integer(Map<String, Object> row, String key) {
        Object value = row.get(key); return value instanceof Number number ? number.intValue() : null;
    }
    private static Long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key); return value instanceof Number number ? number.longValue() : null;
    }

    public Long getId() { return id; }
    public Long getUniqueId() { return uniqueId; }
    public String getName() { return name; }
    public String getGender() { return gender; }
    public String getNationality() { return nationality; }
    public String getClub() { return club; }
    public ClubEntity getClubEntity() { return clubEntity; }
    public void setClubEntity(ClubEntity clubEntity) { this.clubEntity = clubEntity; }
    public String getDivision() { return division; }
    public Integer getJobId() { return jobId; }
    public String getJob() { return job; }
    public String getDateOfBirth() { return dateOfBirth; }
    public Integer getAge() { return age; }
    public Long getSalaryWeeklyRaw() { return salaryWeeklyRaw; }
    public String getContractEndDate() { return contractEndDate; }
    public Integer getHomeReputation() { return homeReputation; }
    public Integer getCurrentReputation() { return currentReputation; }
    public Integer getWorldReputation() { return worldReputation; }
    public Integer getCa() { return ca; }
    public Integer getPa() { return pa; }
}
