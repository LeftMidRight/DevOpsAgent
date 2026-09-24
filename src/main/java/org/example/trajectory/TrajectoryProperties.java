package org.example.trajectory;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "trajectory")
public class TrajectoryProperties {

    private boolean enabled = true;
    private String baseDir = "./data/trajectories";
    private int maxContentChars = 100_000;
    private boolean forceOnWrite = false;
}
