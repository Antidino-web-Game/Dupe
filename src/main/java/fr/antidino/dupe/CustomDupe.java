package fr.antidino.dupe;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CustomDupe extends JavaPlugin {

    private LuckPerms luckPerms;
    private Map<UUID, Long> cooldowns = new HashMap<>();

    // Configuration des cooldowns par grade (en secondes)
    private Map<String, Integer> gradeCooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        // Vérifier si LuckPerms est présent
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            getLogger().severe("LuckPerms n'est pas installé ! Le plugin va se désactiver.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        luckPerms = LuckPermsProvider.get();

        // Charger la configuration
        saveDefaultConfig();
        loadCooldowns();

        getLogger().info("CustomDupe activé avec cooldowns par grade !");
    }

    private void loadCooldowns() {
        gradeCooldowns.clear();

        // Charger depuis config.yml
        if (getConfig().contains("cooldowns")) {
            for (String grade : getConfig().getConfigurationSection("cooldowns").getKeys(false)) {
                int seconds = getConfig().getInt("cooldowns." + grade);
                gradeCooldowns.put(grade.toLowerCase(), seconds);
            }
        }

        getLogger().info("Cooldowns chargés : " + gradeCooldowns.size() + " grades configurés");
    }

    @Override
    public void onDisable() {
        cooldowns.clear();
        getLogger().info("CustomDupe désactivé");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cCette commande est réservée aux joueurs !");
            return true;
        }

        Player player = (Player) sender;

        if (command.getName().equalsIgnoreCase("dupe")) {
            return handleDupe(player, args);
        }

        if (command.getName().equalsIgnoreCase("dupereload")) {
            if (!player.hasPermission("customdupe.reload")) {
                player.sendMessage("§cVous n'avez pas la permission !");
                return true;
            }

            reloadConfig();
            loadCooldowns();
            player.sendMessage("§a✓ Configuration rechargée !");
            return true;
        }

        return false;
    }

    private boolean handleDupe(Player player, String[] args) {
        ItemStack item = player.getInventory().getItemInMainHand();

        // Vérifier si le joueur tient un item
        if (item == null || item.getType().isAir()) {
            player.sendMessage("§cVous devez tenir un item dans votre main !");
            return true;
        }

        // Vérifier le cooldown
        int cooldownSeconds = getCooldownForPlayer(player);

        if (cooldownSeconds > 0) {
            UUID playerId = player.getUniqueId();
            long currentTime = System.currentTimeMillis();

            if (cooldowns.containsKey(playerId)) {
                long lastUse = cooldowns.get(playerId);
                long timeLeft = (lastUse + (cooldownSeconds * 1000L)) - currentTime;

                if (timeLeft > 0) {
                    int secondsLeft = (int) (timeLeft / 1000) + 1;
                    player.sendMessage("§c§l⏳ §cAttendez encore §e" + formatTime(secondsLeft) +
                            "§c avant de dupliquer à nouveau !");
                    return true;
                }
            }

            // Enregistrer l'utilisation
            cooldowns.put(playerId, currentTime);
        }

        // Déterminer le nombre de duplications
        int times = 1;
        if (args.length > 0) {
            try {
                times = Integer.parseInt(args[0]);
                if (times < 1)
                    times = 1;
                if (times > 64)
                    times = 64; // Limite à 64 pour éviter les abus
            } catch (NumberFormatException e) {
                player.sendMessage("§cNombre invalide !");
                return true;
            }
        }

        // Dupliquer l'item
        for (int i = 0; i <= times; i++) {
            ItemStack duplicated = item.clone();

            if (player.getInventory().addItem(duplicated).isEmpty()) {
                // Item ajouté avec succès
            } else {
                // Inventaire plein, dropper au sol
                player.getWorld().dropItem(player.getLocation(), duplicated);
            }
        }

        String itemName = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                ? item.getItemMeta().getDisplayName()
                : item.getType().toString();

        if (times == 1) {
            player.sendMessage("§a§l✓ §7Item §f" + itemName + " §7dupliqué !");
        } else {
            player.sendMessage("§a§l✓ §7Item §f" + itemName + " §7dupliqué §fx" + times + " §7!");
        }

        return true;
    }

    private int getCooldownForPlayer(Player player) {
        // Récupérer l'utilisateur LuckPerms
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null)
            return getConfig().getInt("cooldowns.default", 60);

        // Récupérer le groupe principal
        String primaryGroup = user.getPrimaryGroup();

        // Vérifier si un cooldown est défini pour ce groupe
        if (gradeCooldowns.containsKey(primaryGroup.toLowerCase())) {
            return gradeCooldowns.get(primaryGroup.toLowerCase());
        }

        // Cooldown par défaut
        return getConfig().getInt("cooldowns.default", 60);
    }

    private String formatTime(int seconds) {
        if (seconds >= 60) {
            int minutes = seconds / 60;
            int secs = seconds % 60;
            if (secs == 0) {
                return minutes + " minute" + (minutes > 1 ? "s" : "");
            }
            return minutes + " minute" + (minutes > 1 ? "s" : "") + " et " + secs + " seconde" + (secs > 1 ? "s" : "");
        }
        return seconds + " seconde" + (seconds > 1 ? "s" : "");
    }
}