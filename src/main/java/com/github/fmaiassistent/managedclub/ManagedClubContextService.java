package com.github.fmaiassistent.managedclub;

import com.github.fmaiassistent.ai.AiPromptContextContributor;
import com.github.fmaiassistent.domain.entity.ClubEntity;
import com.github.fmaiassistent.repository.ClubRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Locale;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ManagedClubContextService implements AiPromptContextContributor {
    private static final Logger log = LoggerFactory.getLogger(ManagedClubContextService.class);

    private final ManagedClubMemoryReader memoryReader;
    private final ClubRepository clubs;
    private final AtomicLong versions = new AtomicLong();
    private final AtomicReference<ManagedClubContext> current =
            new AtomicReference<>(ManagedClubContext.notLoaded(0));
    private final Cache<String, Long> deliveredVersions = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofHours(12))
            .build();
    private final AtomicBoolean aiContextEnabled = new AtomicBoolean(true);

    ManagedClubContextService(ManagedClubMemoryReader memoryReader, ClubRepository clubs) {
        this.memoryReader = memoryReader;
        this.clubs = clubs;
    }

    public ManagedClubContext current() {
        return current.get();
    }

    public boolean aiContextEnabled() {
        return aiContextEnabled.get();
    }

    public void setAiContextEnabled(boolean enabled) {
        if (aiContextEnabled.getAndSet(enabled) != enabled) {
            deliveredVersions.invalidateAll();
        }
    }

    public ManagedClubContext refresh(int pid, int build, Long gamePluginBase) throws IOException {
        return publish(detect(pid, build, gamePluginBase));
    }

    /** Detects a managed club without publishing it to application state. */
    public ManagedClubContext detect(int pid, int build, Long gamePluginBase) throws IOException {
        ManagedClubIdentity identity = memoryReader.read(pid, build, gamePluginBase);
        if (identity.unemployed()) {
            return ManagedClubContext.unemployed(versions.incrementAndGet(), identity.managerName(), identity.managerUniqueId());
        }
        ClubEntity club = identity.clubAddress() == 0 ? null
                : clubs.findFirstBySourceAddressOrderByReputationDesc(identity.clubAddress()).orElse(null);
        return new ManagedClubContext(
                versions.incrementAndGet(),
                identity.clubAddress() == 0 ? ManagedClubContext.State.NATIONAL_TEAM : ManagedClubContext.State.AVAILABLE,
                identity.managerName(),
                identity.managerUniqueId(),
                identity.clubName(),
                club == null ? "" : value(club.getCompetition()),
                club == null ? "" : value(club.getNation()),
                club == null ? "" : value(club.getGender()),
                club == null ? null : club.getReputation(),
                club == null ? null : club.getBalance(),
                club == null ? null : club.getTransferBudget(),
                club == null ? null : club.getPayrollBudget(),
                identity.clubAddress() == 0 ? null : identity.clubAddress(),
                identity.clubAddress() == 0 ? "Managing national team " + identity.nationalTeam().name()
                        + "; no managed club. Club transfers and club fixtures are unavailable."
                        : "Detected from the loaded FM27 save",
                identity.nationalTeam());
    }

    public ManagedClubContext publish(ManagedClubContext context) {
        java.util.Objects.requireNonNull(context, "context");
        current.set(context);
        log.info("Detected current human manager={} managedClub={} clubAddress=0x{}",
                context.managerName(), context.clubName(),
                context.clubAddress() == null ? "unknown" : Long.toHexString(context.clubAddress()));
        return context;
    }

    /** Creates an unavailable state without exposing it before a snapshot commits. */
    public ManagedClubContext unavailable(String message) {
        String safeMessage = message == null || message.isBlank()
                ? "The managed club could not be detected from FM27 RAM"
                : message;
        return ManagedClubContext.unavailable(versions.incrementAndGet(), safeMessage);
    }

    public ManagedClubContext markUnavailable(String message) {
        return publish(unavailable(message));
    }

    public void restore(ManagedClubContext context) {
        if (context != null) {
            current.set(context);
        }
    }

    public String currentCareerKey() {
        return current.get().careerKey();
    }

    @Override
    public String contextFor(String conversationKey) {
        if (!aiContextEnabled.get()) {
            return "";
        }
        ManagedClubContext context = current.get();
        if (!context.adviceAvailable() && context.state() != ManagedClubContext.State.UNEMPLOYED) {
            return "";
        }
        Long previousVersion = deliveredVersions.getIfPresent(conversationKey);
        deliveredVersions.put(conversationKey, context.version());
        if (previousVersion != null && previousVersion == context.version()) {
            return "";
        }
        if (context.state() == ManagedClubContext.State.UNEMPLOYED) {
            return """
                    <fm27_managed_club_context>
                    Human manager: %s
                    Human manager FM Unique ID: %s
                    Employment status: unemployed
                    Managed club: none
                    Managed-club squad, transfers, lineup and fixtures are unavailable. Do not infer a club or ask to reload data to fix unemployment.
                    General searches and analysis of explicitly named clubs remain available.
                    </fm27_managed_club_context>
                    """.formatted(context.managerName(), unknown(context.managerUniqueId()));
        }
        String nationalContext = context.nationalTeamAvailable() ? """
                <fm27_national_team_context>
                Managed national team: %s
                Nation FM Unique ID: %s
                Use fm27_get_national_team_context for player selection and tactic fit. fm27_analyze_squad and fm27_optimize_lineup accept this nation name.
                Candidates match loaded primary nationality only; international eligibility, secondary nationality, cap ties, age-group and team gender eligibility are unverified. Confirm these before recommending callups.
                International duty is not an exclusion for national-team selection. Club transfers and budgets do not apply to a national job.
                </fm27_national_team_context>
                """.formatted(context.nationalTeam().name(), context.nationalTeam().nationUniqueId()) : "";
        if (!context.available()) {
            return "Human manager: " + context.managerName() + "\nManaged club: none\n" + nationalContext;
        }
        return nationalContext + """
                <fm27_managed_club_context>
                Human manager: %s
                Human manager FM Unique ID: %s
                Managed club: %s
                Competition: %s
                Nation: %s
                Team gender: %s
                Club reputation: %s
                Balance: %s
                Transfer budget: %s
                Payroll budget: %s
                Data source: live FM27 RAM at the most recent application load
                </fm27_managed_club_context>
                """.formatted(
                context.managerName(), unknown(context.managerUniqueId()), context.clubName(), unknown(context.competition()),
                unknown(context.nation()), unknown(context.gender()), unknown(context.reputation()),
                pounds(context.balance()), pounds(context.transferBudget()), pounds(context.payrollBudget()));
    }

    public String enrich(String conversationKey, String userMessage) {
        String context = contextFor(conversationKey);
        return context.isBlank() ? userMessage : context + "\n\nUser message:\n" + userMessage;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String unknown(Object value) {
        return value == null || String.valueOf(value).isBlank() ? "Unknown" : String.valueOf(value);
    }

    private static String pounds(Long value) {
        return value == null ? "Unknown" : "£" + String.format(Locale.ROOT, "%,d", value);
    }
}
