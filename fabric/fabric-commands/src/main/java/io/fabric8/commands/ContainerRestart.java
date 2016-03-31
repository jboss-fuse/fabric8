package io.fabric8.commands;

import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.gogo.commands.Action;
import org.apache.felix.gogo.commands.basic.AbstractCommand;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.felix.service.command.Function;

import io.fabric8.api.FabricService;
import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.boot.commands.support.AbstractCommandComponent;
import io.fabric8.boot.commands.support.ContainerCompleter;
import io.fabric8.commands.support.StartedContainerCompleter;
import io.fabric8.zookeeper.curator.CuratorFrameworkLocator;

/**
 * Container restart command component class.
 * Created by avano on 24.3.16.
 */
@Component(immediate = true)
@Service({ Function.class, AbstractCommand.class })
@org.apache.felix.scr.annotations.Properties({
		@Property(name = "osgi.command.scope", value = ContainerRestart.SCOPE_VALUE),
		@Property(name = "osgi.command.function", value = ContainerRestart.FUNCTION_VALUE)
})
public class ContainerRestart extends AbstractCommandComponent {
	public static final String SCOPE_VALUE = "fabric";
	public static final String FUNCTION_VALUE = "container-restart";
	public static final String DESCRIPTION = "Restart specified containers";

	@Reference(referenceInterface = FabricService.class)
	private final ValidatingReference<FabricService> fabricService = new ValidatingReference<>();

	// Completers
	@Reference(referenceInterface = ContainerCompleter.class, bind = "bindContainerCompleter", unbind = "unbindContainerCompleter")
	private ContainerCompleter containerCompleter; // dummy field

	@Activate
	void activate() {
		activateComponent();
	}

	@Deactivate
	void deactivate() {
		deactivateComponent();
	}

	@Override
	public Action createNewAction() {
		assertValid();
		CuratorFramework curator = CuratorFrameworkLocator.getCuratorFramework();
		return new ContainerRestartAction(fabricService.get(), curator);
	}

	void bindFabricService(FabricService fabricService) {
		this.fabricService.bind(fabricService);
	}

	void unbindFabricService(FabricService fabricService) {
		this.fabricService.unbind(fabricService);
	}

	void bindContainerCompleter(ContainerCompleter completer) {
		bindCompleter(completer);
	}

	void unbindContainerCompleter(ContainerCompleter completer) {
		unbindCompleter(completer);
	}
}
