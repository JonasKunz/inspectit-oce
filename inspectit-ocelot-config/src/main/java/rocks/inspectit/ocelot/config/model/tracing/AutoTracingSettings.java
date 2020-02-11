package rocks.inspectit.ocelot.config.model.tracing;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;

@Data
@NoArgsConstructor
public class AutoTracingSettings {

    private Duration frequency;

    private Duration shutdownDelay;
}
