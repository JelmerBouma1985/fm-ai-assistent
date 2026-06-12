package com.github.fmaiscout.web.mapper;

import com.github.fmaiscout.config.JCacheConfiguration;
import com.github.fmaiscout.db.PlayerEntity;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

@Service
public class PlayerMapper implements Function<PlayerEntity, Map<String, Object>> {

    @Cacheable(cacheNames = JCacheConfiguration.PLAYER_MAPPING_CACHE)
    @Override
    public Map<String, Object> apply(PlayerEntity entity) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ID", entity.getId());

        if (entity.getClubEntity() != null) {
            out.put("CLUB_ID", entity.getClubEntity().getId());
        } else {
            out.put("CLUB_ID", null);
        }
        if (entity.getPlayingClubEntity() != null) {
            out.put("PLAYING_CLUB_ID", entity.getPlayingClubEntity().getId());
            out.put("PLAYING_NATION", entity.getPlayingClubEntity().getNation());
            out.put("PLAYING_COMPETITION", entity.getPlayingClubEntity().getCompetition());
        } else {
            out.put("PLAYING_CLUB_ID", null);
            out.put("PLAYING_NATION", null);
            out.put("PLAYING_COMPETITION", null);
        }

        out.put("PLAYER_INDEX", entity.getPlayerIndex());
        out.put("GENDER", entity.getGender());
        out.put("RECORD_ADDRESS", entity.getRecordAddress());
        out.put("NAME", entity.getName());
        out.put("NATIONALITY", entity.getNationality());
        out.put("CLUB", entity.getClub());
        out.put("PLAYING_CLUB", entity.getPlayingClub());
        out.put("LOAN_CLUB", entity.getLoanClub());
        out.put("IS_LOANED_OUT", entity.getIsLoanedOut());
        out.put("CURRENT_REPUTATION", entity.getCurrentReputation());
        out.put("HOME_REPUTATION", entity.getHomeReputation());
        out.put("WORLD_REPUTATION", entity.getWorldReputation());
        out.put("CA", entity.getCa());
        out.put("PA", entity.getPa());
        out.put("ASKING_PRICE", entity.getAskingPrice());
        out.put("ASKING_PRICE_RAW", entity.getAskingPriceRaw());
        out.put("CONTRACT_END_DATE", entity.getContractEndDate());
        out.put("SALARY_PA", entity.getSalaryPa());
        out.put("SALARY_WEEKLY_RAW", entity.getSalaryWeeklyRaw());
        out.put("DATE_OF_BIRTH", entity.getDateOfBirth());
        out.put("AGE", entity.getAge());
        out.put("AGE_AS_OF", entity.getAgeAsOf());
        out.put("HEIGHT_CM", entity.getHeightCm());
        out.put("GOALKEEPER", entity.getGoalkeeper());
        out.put("DEFENDER_LEFT", entity.getDefenderLeft());
        out.put("DEFENDER_CENTRAL", entity.getDefenderCentral());
        out.put("DEFENDER_RIGHT", entity.getDefenderRight());
        out.put("WING_BACK_RIGHT", entity.getWingBackRight());
        out.put("DEFENSIVE_MIDFIELDER", entity.getDefensiveMidfielder());
        out.put("WING_BACK_LEFT", entity.getWingBackLeft());
        out.put("MIDFIELDER_LEFT", entity.getMidfielderLeft());
        out.put("MIDFIELDER_CENTRAL", entity.getMidfielderCentral());
        out.put("MIDFIELDER_RIGHT", entity.getMidfielderRight());
        out.put("ATTACKING_MIDFIELDER_LEFT", entity.getAttackingMidfielderLeft());
        out.put("ATTACKING_MIDFIELDER_CENTRAL", entity.getAttackingMidfielderCentral());
        out.put("ATTACKING_MIDFIELDER_RIGHT", entity.getAttackingMidfielderRight());
        out.put("STRIKER", entity.getStriker());
        out.put("ACCELERATION", entity.getAcceleration());
        out.put("AERIALABILITY", entity.getAerialAbility());
        out.put("AGGRESSION", entity.getAggression());
        out.put("AGILITY", entity.getAgility());
        out.put("ANTICIPATION", entity.getAnticipation());
        out.put("BALANCE", entity.getBalance());
        out.put("BRAVERY", entity.getBravery());
        out.put("COMMANDOFAREA", entity.getCommandOfArea());
        out.put("COMMUNICATION", entity.getCommunication());
        out.put("COMPOSURE", entity.getComposure());
        out.put("CONCENTRATION", entity.getConcentration());
        out.put("CONSISTENCY", entity.getConsistency());
        out.put("CORNERS", entity.getCorners());
        out.put("CROSSING", entity.getCrossing());
        out.put("DECISIONS", entity.getDecisions());
        out.put("DETERMINATION", entity.getDetermination());
        out.put("DIRTINESS", entity.getDirtiness());
        out.put("DRIBBLING", entity.getDribbling());
        out.put("ECCENTRICITY", entity.getEccentricity());
        out.put("FINISHING", entity.getFinishing());
        out.put("FIRSTTOUCH", entity.getFirstTouch());
        out.put("FLAIR", entity.getFlair());
        out.put("FREEKICKS", entity.getFreeKicks());
        out.put("HANDLING", entity.getHandling());
        out.put("HEADING", entity.getHeading());
        out.put("IMPORTANTMATCHES", entity.getImportantMatches());
        out.put("INJURYPRONENESS", entity.getInjuryProneness());
        out.put("JUMPINGREACH", entity.getJumpingReach());
        out.put("KICKING", entity.getKicking());
        out.put("LEADERSHIP", entity.getLeadership());
        out.put("LEFTFOOT", entity.getLeftFoot());
        out.put("LONGSHOTS", entity.getLongShots());
        out.put("LONGTHROWS", entity.getLongThrows());
        out.put("MARKING", entity.getMarking());
        out.put("NATURALFITNESS", entity.getNaturalFitness());
        out.put("OFFTHEBALL", entity.getOffTheBall());
        out.put("ONEONONES", entity.getOneOnOnes());
        out.put("PACE", entity.getPace());
        out.put("PASSING", entity.getPassing());
        out.put("PENALTIES", entity.getPenalties());
        out.put("POSITIONING", entity.getPositioning());
        out.put("REFLEXES", entity.getReflexes());
        out.put("RIGHTFOOT", entity.getRightFoot());
        out.put("RUSHINGOUT", entity.getRushingOut());
        out.put("STAMINA", entity.getStamina());
        out.put("STRENGTH", entity.getStrength());
        out.put("TACKLING", entity.getTackling());
        out.put("TEAMWORK", entity.getTeamwork());
        out.put("TECHNIQUE", entity.getTechnique());
        out.put("TENDENCYTOPUNCH", entity.getTendencyToPunch());
        out.put("THROWING", entity.getThrowing());
        out.put("VERSATILITY", entity.getVersatility());
        out.put("VISION", entity.getVision());
        out.put("WORKRATE", entity.getWorkRate());
        out.put("ADAPTABILITY", entity.getAdaptability());
        out.put("AMBITION", entity.getAmbition());
        out.put("LOYALTY", entity.getLoyalty());
        out.put("PRESSURE", entity.getPressure());
        out.put("PROFESSIONALISM", entity.getProfessionalism());
        out.put("SPORTSMANSHIP", entity.getSportsmanship());
        out.put("TEMPERAMENT", entity.getTemperament());
        out.put("CONTROVERSY", entity.getControversy());
        return out;
    }

}
