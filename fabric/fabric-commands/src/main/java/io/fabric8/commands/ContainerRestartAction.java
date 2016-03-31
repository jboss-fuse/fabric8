package io.fabric8.commands;

import static io.fabric8.utils.FabricValidations.validateContainerName;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.exists;

import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.gogo.commands.Command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import io.fabric8.api.Container;
import io.fabric8.api.ContainerProvider;
import io.fabric8.api.CreateContainerMetadata;
import io.fabric8.api.FabricService;

/**
 * Class for the container-restart action.
 * Created by avano on 24.3.16.
 */

@Command(name = ContainerRestart.FUNCTION_VALUE, scope = ContainerRestart.SCOPE_VALUE, description = ContainerRestart.DESCRIPTION,
		detailedDescription = "classpath:containerRestart.txt")
public class ContainerRestartAction extends AbstractContainerLifecycleAction {
	private static final Logger LOGGER = LoggerFactory.getLogger(ContainerRestartAction.class);
	private static final long TIMEOUT = 120000L;
	private CuratorFramework curator;
	private boolean restartCurrentContainer = false;
	private List<Container> restartedContainers = new ArrayList<>();

	public ContainerRestartAction(FabricService fabricService, CuratorFramework curator) {
		super(fabricService);
		this.curator = curator;
	}

	@Override
	protected Object doExecute() throws Exception {
		List<Container> containerList = getEligibleContainers(super.expandGlobNames(containers));

		// First execute stop on all containers
		for (Container c : containerList) {
			try {
				fabricService.stopContainer(c, force);
				restartedContainers.add(c);
			} catch (UnsupportedOperationException uoe) {
				// Usecase for managed containers that are not created using Fabric - joined containers for example
				if (uoe.getMessage().contains("has not been created using Fabric")) {
					LOGGER.warn("Container " + c.getId() + " has not been created using Fabric, skipping restart.");
				} else {
					throw uoe;
				}
			}
		}

		// Wait for all containers to stop
		for (Container c : restartedContainers) {
			long startedAt = System.currentTimeMillis();
			// Wait until the zk node /fabric/registry/containers/status/<container name>/pid disappears
			// Or until the time is up
			// Let this cycle pass atleast 1 time to make it more reliable
			do {
				try {
					Thread.sleep(3000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return null;
				}
			} while (!Thread.interrupted() && startedAt + TIMEOUT > System.currentTimeMillis() &&
					exists(curator, "/fabric/registry/containers/status/" + c.getId() + "/pid") != null);
		}

		// Start the containers back
		for (Container c : restartedContainers) {
			c.start(force);
		}

		printRestartedContainers(restartedContainers);

		if (restartCurrentContainer) {
			System.setProperty("karaf.restart.jvm", "true");
			System.out.println("Restart flag for container \'" + fabricService.getCurrentContainerName() + "\' set,"
					+ " please stop the container manually.");
		}

		return null;
	}

	/**
	 * Prints the restarted containers list.
	 * @param containerList restarted containers list
	 */
	private void printRestartedContainers(List<Container> containerList) {
		if (containerList.size() == 0) {
			return;
		}

		StringBuilder containers = new StringBuilder("The list of restarted containers: [");
		for (Container c : containerList) {
			containers.append(c.getId());
			containers.append(", ");
		}
		containers.setLength(containers.length() - 2);
		containers.append("]");
		System.out.println(containers.toString());
	}

	/**
	 * Pick the containers eligible for restart from the collection of container names.
	 * @param names container names
	 * @return list of containers eligible for restart
	 */
	private List<Container> getEligibleContainers(Collection<String> names) {
		List<Container> containerList = new ArrayList<>();
		for (String containerName : names) {
			validateContainerName(containerName);
			if (containerName.equals(fabricService.getCurrentContainerName())) {
				if (fabricService.getCurrentContainer().isRoot() && !isSshContainer(fabricService.getCurrentContainer())) {
					restartCurrentContainer = true;
				} else {
					LOGGER.warn("Container " + containerName + " can't be restarted from itself");
				}
				continue;
			}
			if (!fabricService.getContainer(containerName).isManaged()) {
				LOGGER.warn("Container " + containerName + " is not managed by Fabric, skipping restart.");
				continue;
			}
			containerList.add(fabricService.getContainer(containerName));
		}
		return containerList;
	}

	/**
	 * Checks if the container is an SSH container.
	 * @param container container
	 * @return true/false
	 */
	private boolean isSshContainer(Container container) {
		CreateContainerMetadata metadata = container.getMetadata();
		String type = metadata != null ? metadata.getCreateOptions().getProviderType() : null;

		if (type == null) {
			return false;
		}

		ContainerProvider provider = fabricService.getProvider(type);

		if (provider == null) {
			return false;
		}

		return provider == null ? false : "ssh".equals(provider.getScheme());
	}
}
