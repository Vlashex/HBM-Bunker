package dev.deadearth.legacy.core;

import java.util.Map;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.Name("DeadEarthSrpGuards")
@IFMLLoadingPlugin.SortingIndex(1100)
@IFMLLoadingPlugin.TransformerExclusions({"dev.deadearth.legacy.core."})
public final class LegacyCore implements IFMLLoadingPlugin {
  public String[] getASMTransformerClass() {
    return new String[] {"dev.deadearth.legacy.core.LegacyTransformer"};
  }

  public String getModContainerClass() {
    return null;
  }

  public String getSetupClass() {
    return null;
  }

  public void injectData(Map<String, Object> data) {}

  public String getAccessTransformerClass() {
    return null;
  }
}
