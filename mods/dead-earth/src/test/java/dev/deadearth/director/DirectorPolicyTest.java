package dev.deadearth.director;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class DirectorPolicyTest {
    final UUID campaign=UUID.randomUUID(), epoch=UUID.randomUUID();
    final DirectorPolicy.Settings settings=new DirectorPolicy.Settings(true,18000,2400,1200);
    final List<DirectorPolicy.Candidate> zones=List.of(
        new DirectorPolicy.Candidate("a",10,20,true,false),
        new DirectorPolicy.Candidate("b",100,20,false,false));
    DirectorPolicy.Brief brief(DirectorPolicy policy,long now) { return policy.brief(campaign,epoch,now,1,settings,zones); }
    @Test void choosesKnownZoneInsteadOfUnknownHighScoreAndReusesGoal() {
        var p=new DirectorPolicy();var first=brief(p,0);
        assertEquals("zone:a",first.goal().targetId());assertEquals(1,first.candidates().size());
        assertEquals(first.goal().id(),brief(p,200).goal().id());
        assertEquals(18000,first.nextDecisionActiveTicks());
    }
    @Test void noPlayersOrDisabledNeverIssuesGoal() {
        var p=new DirectorPolicy();
        assertNull(p.brief(campaign,epoch,0,0,settings,zones).goal());
        assertNull(p.brief(campaign,epoch,0,1,new DirectorPolicy.Settings(false,18000,2400,1200),zones).goal());
    }
    @Test void fallbackObservesCampaignWithoutInventingZoneFacts() {
        var b=new DirectorPolicy().brief(campaign,epoch,0,1,settings,List.of(
            new DirectorPolicy.Candidate("a",Double.NaN,0,true,false),
            new DirectorPolicy.Candidate("b",20,1201,true,false),
            new DirectorPolicy.Candidate("c",20,0,true,true)));
        assertEquals("campaign",b.goal().targetId());assertEquals("observe_campaign",b.goal().tactic());
    }
    @Test void validatesPreviewWithoutChangingGoalOrBudget() {
        var p=new DirectorPolicy();var g=brief(p,0).goal();var id=UUID.randomUUID();
        var v=p.preview(id,g.id(),"distant_sound","Вдалеке слышен шорох.",campaign,epoch,100,1,settings,zones);
        assertTrue(v.accepted());assertTrue(v.previewOnly());assertEquals(id,v.requestId());
        assertEquals(g.id(),brief(p,101).goal().id());
    }
    @Test void staleDeletedChangedContextAndPolicyAreRejected() {
        var p=new DirectorPolicy();var g=brief(p,0).goal();
        assertFalse(p.preview(UUID.randomUUID(),g.id(),"radio_static","Шорох.",campaign,epoch,2400,1,settings,zones).accepted());
        assertFalse(p.preview(UUID.randomUUID(),g.id(),"radio_static","Шорох.",campaign,epoch,20,1,settings,List.of()).accepted());
        assertFalse(p.preview(UUID.randomUUID(),g.id(),"radio_static","Шорох.",campaign,UUID.randomUUID(),20,1,settings,zones).accepted());
        assertFalse(p.preview(UUID.randomUUID(),g.id(),"radio_static","Шорох.",campaign,epoch,20,0,settings,zones).accepted());
        assertFalse(p.preview(UUID.randomUUID(),g.id(),"radio_static","Шорох.",campaign,epoch,20,1,new DirectorPolicy.Settings(true,20000,2400,1200),zones).accepted());
    }
    @Test void cadenceUsesVirtualTimeWithoutLongWaits() {
        var p=new DirectorPolicy();var first=brief(p,0);
        assertNull(brief(p,2400).goal());assertNull(brief(p,17999).goal());
        assertNotEquals(first.goal().id(),brief(p,18000).goal().id());
    }
    @Test void narrativeIsPlainBoundedText() {
        for(var t:List.of("", " x","x\nrun", "a".repeat(181),"\u00a7cFake","\u202eFake","\ud800")) assertFalse(DirectorPolicy.validNarrative("radio_static",t));
        assertFalse(DirectorPolicy.validNarrative("command","Наблюдение."));
        assertTrue(DirectorPolicy.validNarrative("spore_trace","На поверхности замечена пыль."));
    }
}
