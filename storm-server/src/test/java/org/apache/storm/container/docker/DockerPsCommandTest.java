package org.apache.storm.container.docker;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

public class DockerPsCommandTest {

    private DockerPsCommand dockerPsCommand;

    @Before
    public void setup() {
        dockerPsCommand = new DockerPsCommand();
    }

    @Test
    public void getCommandOption() {
        assertEquals("ps", dockerPsCommand.getCommandOption());
    }

    @Test
    public void getCommandWithArguments() {
        dockerPsCommand.withNameFilter("container_name");
        dockerPsCommand.withQuietOption();
        assertEquals("ps --filter=name=container_name --quiet=true",
            dockerPsCommand.getCommandWithArguments());
    }
}