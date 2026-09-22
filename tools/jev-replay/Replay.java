import com.melody.mcagent.rt.llm.JevClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Re-score recorded JEV decision states offline.
 *
 * <p>Shadow mode writes the exact state it sent to Jev into the server log. This reads those states
 * back and asks the real model again, so a threshold can be chosen from data instead of taste - and
 * so a change to the state or the prompt can be judged against the same sample set.
 *
 * <p>Input: one case per line, {@code <event>\t<state>}. Build it with {@code extract.sh}.
 * Usage: {@code java -Dkey=... -Dendpoint=... -Dmodel=... -Dthreshold=0.6 Replay cases.tsv}
 */
public final class Replay {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: Replay <cases.tsv>  (-Dkey= -Dendpoint= -Dmodel= -Dthreshold=)");
            return;
        }
        String key = System.getProperty("key", "");
        String endpoint = System.getProperty("endpoint", "https://ai-gateway.vercel.sh/v1/evaluate");
        String model = System.getProperty("model", "typesafe-ai/jev");
        double threshold = Double.parseDouble(System.getProperty("threshold", "0.6"));
        JevClient client = new JevClient(new JevClient.Settings(true, endpoint, key, model,
                8000, true, JevClient.GateMode.ACTIVE, JevClient.GateMode.SHADOW,
                JevClient.Protocol.SYSTEM_ONE, 800));

        Map<String, String> speech = new LinkedHashMap<>();
        speech.put("SPEAK", "Answer now");
        speech.put("STAY_SILENT", "Say nothing");
        Map<String, String> mining = new LinkedHashMap<>();
        mining.put("RETRY_DIFFERENT_ACCESS", "Retry this target once from the changed world");
        mining.put("SKIP_TARGET", "Mark this exact target temporarily unreachable");
        mining.put("BACKTRACK", "Return along known mine breadcrumbs toward the entrance");
        mining.put("GATHER_PERCEPTION", "Inspect local geometry and blockers first");
        mining.put("ESCALATE_LLM", "Ask the planning LLM");
        Map<String, String> routing = new LinkedHashMap<>();
        routing.put("CONTINUE", "Carry on with the work already queued; no new plan needed");
        routing.put("ESCALATE_LLM", "Think again before acting");

        List<String> lines = Files.readAllLines(Path.of(args[0]));
        Map<String, Integer> choices = new TreeMap<>();
        List<Double> confidences = new ArrayList<>();
        int acted = 0;
        int failed = 0;
        for (String line : lines) {
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            String event = line.substring(0, tab).trim();
            String state = line.substring(tab + 1).trim();
            if (state.isEmpty()) {
                continue;
            }
            boolean isSpeech = "SPEECH_GATE".equalsIgnoreCase(event);
            boolean isRouting = "ROUTING".equalsIgnoreCase(event);
            // The exact wording the runtime uses: a replay with paraphrased instructions measures
            // nothing (measured: the same state answered RETRY 0.73 under one wording, GATHER 0.22
            // under another).
            JevClient.Choice choice = client.choose(state,
                    isSpeech ? "speech_gate" : isRouting ? "routing" : "mining_recovery",
                    isSpeech ? com.melody.mcagent.rt.llm.JevPrompts.SPEECH_GATE
                             : isRouting ? com.melody.mcagent.rt.llm.JevPrompts.ROUTING
                             : com.melody.mcagent.rt.llm.JevPrompts.MINING_RECOVERY,
                    isSpeech ? speech : isRouting ? routing : mining);
            if (choice.failed()) {
                failed++;
                System.out.printf("%-12s FAILED %s%n", event, choice.error());
                continue;
            }
            // Space the calls out: the client opens its breaker after three consecutive failures, and
            // a replay that fires sixteen requests back to back can trip it on its own - which then
            // looks like "the endpoint refused everything" in the summary.
            long gap = Long.getLong("sleepMillis", 400L);
            if (gap > 0L) {
                Thread.sleep(gap);
            }
            choices.merge(choice.choice(), 1, Integer::sum);
            confidences.add(choice.confidence());
            boolean wouldAct = choice.confidence() >= threshold;
            if (wouldAct) {
                acted++;
            }
            System.out.printf("%-12s %-24s %.2f  %s%n", event, choice.choice(), choice.confidence(),
                    wouldAct ? "ACT" : "abstain");
        }
        confidences.sort(Double::compare);
        System.out.println("---");
        System.out.println("cases=" + confidences.size() + " failed=" + failed);
        System.out.println("choices=" + choices);
        if (!confidences.isEmpty()) {
            System.out.printf("confidence min=%.2f median=%.2f max=%.2f%n",
                    confidences.get(0), confidences.get(confidences.size() / 2),
                    confidences.get(confidences.size() - 1));
        }
        System.out.println("would act at threshold " + threshold + ": " + acted + "/"
                + confidences.size());
    }
}
