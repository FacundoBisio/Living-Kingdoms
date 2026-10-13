package dev.livingkingdoms.profession;

import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/** Native door handling after a worker's vanilla Brain behaviors are suspended. */
public final class WorkDoors {
    private static final Map<Villager,WeakReference<OpenDoorGoal>> GOALS=new WeakHashMap<>();
    private WorkDoors() {}
    public static void ensure(Villager worker) {
        if(worker.getNavigation() instanceof GroundPathNavigation ground) {ground.setCanOpenDoors(true);ground.setCanPassDoors(true);ground.setCanFloat(true);}
        var reference=GOALS.get(worker);if(reference!=null && reference.get()!=null) return;
        var goal=new OpenDoorGoal(worker,true);worker.goalSelector.addGoal(0,goal);GOALS.put(worker,new WeakReference<>(goal));
    }
    public static void release(Villager worker) {var reference=GOALS.remove(worker);if(reference!=null && reference.get()!=null) worker.goalSelector.removeGoal(reference.get());}
}
