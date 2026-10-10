package net.vulkanmod;

import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;
import net.vulkanmod.config.Config;
import net.vulkanmod.config.Platform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Initializer {
   public static final Logger LOGGER = LogManager.getLogger("VulkanMod");
   private static final String VERSION = "0.6.7";
   public static final Config CONFIG = loadConfig(FMLPaths.CONFIGDIR.get().resolve("vulkanmod_settings.json"));
   private static boolean platformReady;

   public static void onPreGlfwInit() {
      if (platformReady) {
         return;
      }
      platformReady = true;
      LOGGER.info("== VulkanMod {} ==", VERSION);
      Platform.init();
   }

   private static Config loadConfig(Path path) {
      Config config = Config.load(path);
      if (config == null) {
         config = new Config();
         config.write();
      }

      return config;
   }

   public static String getVersion() {
      return VERSION;
   }
}
