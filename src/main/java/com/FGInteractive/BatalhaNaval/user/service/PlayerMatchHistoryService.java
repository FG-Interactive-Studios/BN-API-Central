package com.FGInteractive.BatalhaNaval.user.service;

import com.FGInteractive.BatalhaNaval.match.dto.BattleResponse;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Results are recorded only by the authoritative match lifecycle, never by a client. */
@Service
public class PlayerMatchHistoryService {
    private final JdbcTemplate jdbc;
    public PlayerMatchHistoryService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public record RecentMatch(UUID matchId,String opponentNickname,String modeId,String modeName,
                              String result,String finishReason,Instant finishedAt,int hits,int hitsReceived){}
    public record ProfileStatistics(long totalMatches,long wins,long losses,long draws,
                                    long currentWinStreak,long bestWinStreak,List<RecentMatch> recentMatches){}
    private record Totals(long total,long wins,long losses,long draws,long current,long best){}

    @Transactional
    public void record(long host,long guest,BattleResponse hostView,BattleResponse guestView){
        if(host==guest||!"FINISHED".equals(hostView.status())||!"FINISHED".equals(guestView.status())
            ||!Objects.equals(hostView.matchId(),guestView.matchId())||!Objects.equals(hostView.winnerId(),guestView.winnerId()))
            throw new IllegalArgumentException("Only a consistent finished match can be recorded");
        Long winner=hostView.winnerId();
        if(winner!=null&&winner!=host&&winner!=guest)throw new IllegalArgumentException("Invalid winner");
        if(jdbc.update("INSERT INTO recorded_match_results(match_id) VALUES (?) ON CONFLICT DO NOTHING",hostView.matchId())==0)return;
        String hostName=jdbc.queryForObject("SELECT nickname FROM users WHERE id=?",String.class,host);
        String guestName=jdbc.queryForObject("SELECT nickname FROM users WHERE id=?",String.class,guest);
        Timestamp at=Timestamp.from(Instant.now());
        // Consistent lock order if recording more than one match concurrently.
        if(host<guest){save(host,guestName,hostView,at);save(guest,hostName,guestView,at);}
        else {save(guest,hostName,guestView,at);save(host,guestName,hostView,at);}
    }

    private void save(long player,String opponent,BattleResponse view,Timestamp at){
        boolean won=Objects.equals(view.winnerId(),player),draw=view.winnerId()==null;
        String result=draw?"DRAW":won?"WIN":"LOSS";
        int win=won?1:0,loss=(!won&&!draw)?1:0,tie=draw?1:0;
        jdbc.update("""
            INSERT INTO player_match_statistics(user_id,total_matches,wins,losses,draws,current_win_streak,best_win_streak)
            VALUES (?,1,?,?,?,?,?)
            ON CONFLICT(user_id) DO UPDATE SET
              total_matches=player_match_statistics.total_matches+1,
              wins=player_match_statistics.wins+EXCLUDED.wins,
              losses=player_match_statistics.losses+EXCLUDED.losses,
              draws=player_match_statistics.draws+EXCLUDED.draws,
              current_win_streak=CASE WHEN EXCLUDED.wins=1 THEN player_match_statistics.current_win_streak+1 ELSE 0 END,
              best_win_streak=GREATEST(player_match_statistics.best_win_streak,
                CASE WHEN EXCLUDED.wins=1 THEN player_match_statistics.current_win_streak+1 ELSE 0 END)
            """,player,win,loss,tie,win,win);
        int hits=(int)view.shotsFired().stream().filter(s->s.hit()&&!s.blocked()).count();
        int received=(int)view.shotsReceived().stream().filter(s->s.hit()&&!s.blocked()).count();
        jdbc.update("""
            INSERT INTO player_match_history(user_id,match_id,opponent_nickname,mode_id,mode_name,result,finish_reason,finished_at,hits,hits_received)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """,player,view.matchId(),opponent,view.mode().id(),view.mode().name(),result,view.finishReason(),at,hits,received);
        jdbc.update("""
            DELETE FROM player_match_history WHERE user_id=? AND match_id IN (
              SELECT match_id FROM player_match_history WHERE user_id=?
              ORDER BY finished_at DESC,match_id DESC OFFSET 10)
            """,player,player);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ProfileStatistics statistics(long player){
        Totals t=jdbc.query("""
            SELECT total_matches,wins,losses,draws,current_win_streak,best_win_streak
            FROM player_match_statistics WHERE user_id=?
            """,(rs,n)->new Totals(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getLong(6)),player)
            .stream().findFirst().orElse(new Totals(0,0,0,0,0,0));
        List<RecentMatch> recent=jdbc.query("""
            SELECT match_id,opponent_nickname,mode_id,mode_name,result,finish_reason,finished_at,hits,hits_received
            FROM player_match_history WHERE user_id=? ORDER BY finished_at DESC,match_id DESC LIMIT 10
            """,(rs,n)->new RecentMatch(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getString(5),rs.getString(6),rs.getTimestamp(7).toInstant(),rs.getInt(8),rs.getInt(9)),player);
        return new ProfileStatistics(t.total,t.wins,t.losses,t.draws,t.current,t.best,recent);
    }
}
