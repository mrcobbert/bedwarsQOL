package bench;

import com.bedwarsqol.stats.BedwarsStats;
import com.bedwarsqol.stats.ScraperBackendClient;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Streaming batch check: prints each player's ARRIVAL time + the star follow-up so we can see
 *  counters-first, then stars trickling in via the achievements-page pass. */
public class BatchTest {
    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://127.0.0.1:8787";
        List<String> names = Arrays.asList(
                "gamerboy80", "Technoblade",
                "Ph1LzA", "Antfrost", "Hbomb94", "Eret", "Fundy", "Tubbo", "Ranboo", "WilburSoot");

        System.out.println("== streaming batch of " + names.size() + " (arrival times) ==");
        final long t0 = System.nanoTime();
        final AtomicInteger n = new AtomicInteger();
        final AtomicInteger stars = new AtomicInteger();
        ScraperBackendClient.fetchBatchStreaming(names, base, "",
                (name, s) -> {
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    System.out.printf("  +%5dms  #%-2d %-14s state=%-12s star=%-4d net=%-4d FK=%d%n",
                            ms, n.incrementAndGet(), name, s.state, s.bedwarsLevel, s.networkLevel,
                            s.overall.finalKills);
                },
                (name, level) -> {
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    System.out.printf("  +%5dms  ★  %-14s -> star %d%n", ms, name, level);
                    stars.incrementAndGet();
                });
        long total = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  total " + total + "ms for " + names.size() + " players ("
                + n.get() + " counters, " + stars.get() + " star updates)");
    }
}
