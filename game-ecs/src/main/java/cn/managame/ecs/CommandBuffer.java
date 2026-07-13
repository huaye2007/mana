package cn.managame.ecs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Defers structural world changes until a system iteration has completed. */
public final class CommandBuffer {

    private final World world;
    private final List<Consumer<World>> commands = new ArrayList<>();
    private boolean applied;

    CommandBuffer(World world) {
        this.world = world;
    }

    public CommandBuffer spawn(Component... components) {
        Component[] snapshot = Arrays.copyOf(Objects.requireNonNull(components, "components"), components.length);
        for (Component component : snapshot) {
            Objects.requireNonNull(component, "component");
        }
        append(target -> {
            Entity entity = target.createEntity();
            for (Component component : snapshot) {
                target.add(entity, component);
            }
        });
        return this;
    }

    public CommandBuffer delete(Entity entity) {
        append(target -> target.deleteEntity(entity));
        return this;
    }

    public CommandBuffer add(Entity entity, Component component) {
        Objects.requireNonNull(component, "component");
        append(target -> target.add(entity, component));
        return this;
    }

    public <T extends Component> CommandBuffer remove(Entity entity, Class<T> type) {
        append(target -> target.remove(entity, type));
        return this;
    }

    public int size() {
        return commands.size();
    }

    public void apply() {
        if (applied) {
            throw new IllegalStateException("command buffer has already been applied");
        }
        applied = true;
        commands.forEach(command -> command.accept(world));
        commands.clear();
    }

    private void append(Consumer<World> command) {
        if (applied) {
            throw new IllegalStateException("command buffer has already been applied");
        }
        commands.add(command);
    }
}
