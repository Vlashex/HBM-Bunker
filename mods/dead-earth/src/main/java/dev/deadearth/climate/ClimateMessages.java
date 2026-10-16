package dev.deadearth.climate;

/** Fixed informational radio copy; no physical weather effect is asserted. */
public final class ClimateMessages {
    private ClimateMessages() {}
    public static String text(ClimateCycle.Notice notice) {
        var status=notice.snapshot();long minutes=(status.ticksRemaining()+1199)/1200;
        if (notice.kind()==ClimateCycle.NoticeKind.FORECAST)
            return "[DEAD EARTH] Прогноз цикла: через "+minutes+" мин активной игры — "+label(status.nextPhase(),status.nextTrend())+".";
        return "[DEAD EARTH] Климатический цикл: "+label(status.phase(),status.trend())+". Далее — "
                +label(status.nextPhase(),status.nextTrend())+" через "+minutes+" мин активной игры.";
    }
    private static String label(ClimateCycle.Phase phase, ClimateCycle.Trend trend) {
        return switch (phase) {
            case HEAT -> "жара";
            case ASH_WINTER -> "пепельная зима";
            case CALM -> trend==ClimateCycle.Trend.WARMING ? "затишье с потеплением" : "затишье с охлаждением";
        };
    }
}
