package org.apache.storm.container.docker;

import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang.StringUtils;

public class DockerPsCommand extends DockerCommand {
    private static final String PS_COMMAND = "ps";

    public DockerPsCommand() {
        super(PS_COMMAND);
    }

    public DockerPsCommand withQuietOption() {
        super.addCommandArguments("--quiet=true");
        return this;
    }

    public DockerPsCommand withNameFilter(String containerName) {
        super.addCommandArguments("--filter=name=" + containerName);
        return this;
    }

    /**
     * Get the full command.
     * @return the full command.
     */
    @Override
    public String getCommandWithArguments() {
        List<String> argList = new ArrayList<>();
        argList.add(super.getCommandWithArguments());
        return StringUtils.join(argList, " ");
    }
}
