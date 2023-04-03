/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.pipeline.signal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.connect.source.SourceConnector;
import org.awaitility.Awaitility;
import org.junit.Test;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.connector.SourceInfoStructMaker;
import io.debezium.document.DocumentReader;
import io.debezium.junit.logging.LogInterceptor;
import io.debezium.pipeline.signal.actions.Log;
import io.debezium.pipeline.signal.actions.SignalAction;
import io.debezium.pipeline.spi.Partition;

public class SignalProcessorTest {

    private SignalProcessor<TestPartition> signalProcess;
    private final DocumentReader documentReader = DocumentReader.defaultReader();

    @Test
    public void shouldExecuteLog() {
        final SignalChannelReader genericChannel = new SignalChannelReader() {
            @Override
            public String name() {
                return "generic";
            }

            @Override
            public void init() {
            }

            @Override
            public List<SignalRecord> read() {
                return List.of(new SignalRecord("log1", "log", "{\"message\": \"signallog {}\"}"));
            }

            @Override
            public void close() {
            }
        };

        final LogInterceptor log = new LogInterceptor(Log.class);

        signalProcess = new SignalProcessor<>(SourceConnector.class,
                baseConfig(),
                null,
                List.of(genericChannel), documentReader);

        signalProcess.start();

        Awaitility.await()
                .atMost(40, TimeUnit.SECONDS)
                .until(() -> log.containsMessage("signallog <none>"));
        assertThat(log.containsMessage("signallog <none>")).isTrue();
    }

    @Test
    public void onlyEnabledConnectorShouldExecute() {
        final SignalChannelReader genericChannel1 = new SignalChannelReader() {
            @Override
            public String name() {
                return "generic1";
            }

            @Override
            public void init() {
            }

            @Override
            public List<SignalRecord> read() {
                return List.of(new SignalRecord("log1", "log", "{\"message\": \"signallog {}\"}"));
            }

            @Override
            public void close() {
            }
        };

        final SignalChannelReader genericChannel2 = new SignalChannelReader() {
            @Override
            public String name() {
                return "generic2";
            }

            @Override
            public void init() {
            }

            @Override
            public List<SignalRecord> read() {
                return List.of(new SignalRecord("log1", "log", "{\"message\": \"signallog {}\"}"));
            }

            @Override
            public void close() {
            }
        };

        final LogInterceptor log = new LogInterceptor(Log.class);

        signalProcess = new SignalProcessor<>(SourceConnector.class,
                baseConfig(Map.of(CommonConnectorConfig.SIGNAL_ENABLED_CHANNELS.name(), "generic1")),
                null,
                List.of(genericChannel1, genericChannel2), documentReader);

        signalProcess.start();

        Awaitility.await()
                .atMost(5000, TimeUnit.MILLISECONDS)
                .until(() -> log.containsMessage("signallog <none>"));

        assertThat(log.countOccurrences("signallog {}")).isEqualTo(1);
    }

    @Test
    public void shouldIgnoreInvalidSignalType() {
        final SignalChannelReader genericChannel = new SignalChannelReader() {
            public String name() {
                return "generic";
            }

            @Override
            public void init() {
            }

            @Override
            public List<SignalRecord> read() {
                return List.of(new SignalRecord("log1", "invalidType", "{\"message\": \"signallog {}\"}"));
            }

            @Override
            public void close() {
            }
        };

        final LogInterceptor log = new LogInterceptor(SignalProcessor.class);

        signalProcess = new SignalProcessor<>(SourceConnector.class, baseConfig(), null, List.of(genericChannel), documentReader);

        signalProcess.start();

        Awaitility.await()
                .atMost(40, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(log.containsMessage("Signal 'log1' has been received but the type 'invalidType' is not recognized")).isTrue());

    }

    @Test
    public void shouldIgnoreUnparseableData() {
        final SignalChannelReader genericChannel = new SignalChannelReader() {

            public String name() {
                return "generic";
            }

            @Override
            public void init() {
            }

            @Override
            public List<SignalRecord> read() {
                return List.of(new SignalRecord("log1", "log", "{\"message: \"signallog\"}"));
            }

            @Override
            public void close() {
            }
        };

        final LogInterceptor log = new LogInterceptor(SignalProcessor.class);

        signalProcess = new SignalProcessor<>(SourceConnector.class, baseConfig(), null, List.of(genericChannel), documentReader);

        signalProcess.start();

        Awaitility.await()
                .atMost(40, TimeUnit.SECONDS)
                .untilAsserted(
                        () -> assertThat(log.containsMessage("Signal 'log1' has been received but the data '{\"message: \"signallog\"}' cannot be parsed")).isTrue());
    }

    @Test
    public void shouldRegisterAdditionalAction() {
        final SignalChannelReader genericChannel = new SignalChannelReader() {

            public String name() {
                return "generic";
            }

            @Override
            public void init() {
            }

            @Override
            public List<SignalRecord> read() {
                return List.of(new SignalRecord("log1", "custom", "{\"v\": 5}"));
            }

            @Override
            public void close() {
            }
        };

        final AtomicInteger called = new AtomicInteger();
        final SignalAction<TestPartition> testAction = signalPayload -> {
            called.set(signalPayload.data.getInteger("v"));
            return true;
        };

        signalProcess = new SignalProcessor<>(SourceConnector.class, baseConfig(), null, List.of(genericChannel), documentReader);

        signalProcess.registerSignalAction("custom", testAction);

        signalProcess.start();

        Awaitility.await()
                .atMost(40, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(called.intValue()).isEqualTo(5));
    }

    protected CommonConnectorConfig baseConfig() {
        return baseConfig(Map.of());
    }

    protected CommonConnectorConfig baseConfig(Map<String, Object> additionalConfig) {
        Configuration.Builder confBuilder = Configuration.create()
                .with(CommonConnectorConfig.SIGNAL_DATA_COLLECTION, "debezium.signal")
                .with(CommonConnectorConfig.TOPIC_PREFIX, "core")
                .with(CommonConnectorConfig.SIGNAL_POLL_INTERVAL_MS, 100)
                .with(CommonConnectorConfig.SIGNAL_ENABLED_CHANNELS, "database,generic");

        additionalConfig.forEach(confBuilder::with);
        return new CommonConnectorConfig(confBuilder.build(), 0) {
            @Override
            protected SourceInfoStructMaker<?> getSourceInfoStructMaker(Version version) {
                return null;
            }

            @Override
            public String getContextName() {
                return null;
            }

            @Override
            public String getConnectorName() {
                return null;
            }
        };
    }

    private static class TestPartition implements Partition {
        @Override
        public Map<String, String> getSourcePartition() {
            throw new UnsupportedOperationException();
        }
    }

}
