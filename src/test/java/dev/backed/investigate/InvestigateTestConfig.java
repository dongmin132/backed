package dev.backed.investigate;

import dev.backed.close.CloseOutcome;
import dev.backed.close.CloseRunner;
import dev.backed.close.CloseStatus;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** A scripted investigator and a close runner that only counts runs. No network. */
@TestConfiguration(proxyBeanMethods = false)
public class InvestigateTestConfig {

    @Bean
    @Primary
    public StubInvestigator stubInvestigator() {
        return new StubInvestigator();
    }

    @Bean
    @Primary
    public RecordingCloseRunner recordingCloseRunner() {
        return new RecordingCloseRunner();
    }

    public static class StubInvestigator implements Investigator {

        private volatile boolean configured = true;
        private volatile Function<String, InvestigatorOutput> responder = bundle -> {
            throw new InvestigatorException("no stub output set");
        };
        private final AtomicInteger calls = new AtomicInteger();
        private volatile String lastBundle;

        public void reset() {
            configured = true;
            responder = bundle -> {
                throw new InvestigatorException("no stub output set");
            };
            calls.set(0);
            lastBundle = null;
        }

        public void setConfigured(boolean configured) {
            this.configured = configured;
        }

        public void respond(InvestigatorOutput output) {
            this.responder = bundle -> output;
        }

        public int calls() {
            return calls.get();
        }

        public String lastBundle() {
            return lastBundle;
        }

        @Override
        public boolean configured() {
            return configured;
        }

        @Override
        public String provider() {
            return "stub";
        }

        @Override
        public InvestigatorOutput investigate(String evidenceBundleJson) {
            calls.incrementAndGet();
            lastBundle = evidenceBundleJson;
            return responder.apply(evidenceBundleJson);
        }
    }

    public static class RecordingCloseRunner implements CloseRunner {

        private final AtomicInteger runs = new AtomicInteger();

        public void reset() {
            runs.set(0);
        }

        public int runs() {
            return runs.get();
        }

        @Override
        public CloseOutcome runClose(String actor) {
            runs.incrementAndGet();
            return new CloseOutcome(0, null, CloseStatus.CLOSED, 0, 0, 0, 0, List.of(), 0, List.of());
        }
    }
}
