package fr.antidino.dupe;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.block.ShulkerBox;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.List;

public class CustomDupe extends JavaPlugin {

    private LuckPerms luckPerms;
    private Map<UUID, Long> cooldowns = new HashMap<>();

    // Configuration des cooldowns par grade (en secondes)
    private Map<String, Integer> gradeCooldowns = new HashMap<>();

    // Blacklist d'items (noms de matériaux en lowercase). "shulker" -> match toutes
    // les shulker boxes
    private Set<String> blacklist = new HashSet<>();

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
        loadBlacklist();

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

    private void loadBlacklist() {
        blacklist.clear();
        if (getConfig().contains("blacklist")) {
            List<String> list = getConfig().getStringList("blacklist");
            for (String s : list) {
                if (s != null && !s.trim().isEmpty())
                    blacklist.add(s.toLowerCase());
            }
        }
        getLogger().info("Blacklist chargée : " + blacklist.size() + " entrées");
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
            loadBlacklist();
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

        // Si l'item est blacklisté (ex: "shulker" dans la config) on interdit
        // totalement
        if (isBlacklisted(item)) {
            player.sendMessage("§cCet item est interdit de duplication !");
            return true;
        }

        // Si c'est une shulker, préparer une version filtrée (supprime les items
        // blacklistés
        // à l'intérieur) et compter combien ont été supprimés
        ShulkerFilterResult shulkerResult = null;
        if (item.getItemMeta() instanceof BlockStateMeta) {
            BlockStateMeta meta = (BlockStateMeta) item.getItemMeta();
            if (meta.getBlockState() instanceof ShulkerBox) {
                shulkerResult = filterShulkerContents(item);
                if (shulkerResult.removed > 0) {
                    player.sendMessage("§eAttention : " + shulkerResult.removed
                            + " item(s) blacklisté(s) ont été retirés du contenu de la shulker avant duplication.");
                }
            }
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
                if (times > 4)
                    times = 4;
                player.sendMessage("§cVous avez entrez un nobre trop grand il a donc été dupli 4fois");// Limite à 64
                                                                                                       // pour éviter
                                                                                                       // les abus
            } catch (NumberFormatException e) {
                player.sendMessage("§cNombre invalide !");
                return true;
            }
        }

        // Dupliquer l'item (si shulker -> utiliser la version filtrée préparée)
        for (int i = 0; i < times; i++) {
            ItemStack duplicated;
            if (shulkerResult != null && shulkerResult.stack != null) {
                duplicated = shulkerResult.stack.clone();
            } else {
                duplicated = item.clone();
            }

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

    // Nouveau helper : résultat du filtrage d'une shulker
    private static class ShulkerFilterResult {
        final ItemStack stack;
        final int removed;

        ShulkerFilterResult(ItemStack stack, int removed) {
            this.stack = stack;
            this.removed = removed;
        }
    }

    // Filtre le contenu d'une shulker en enlevant les items blacklists et renvoie
    // la shulker modifiée + nb retirés
    private ShulkerFilterResult filterShulkerContents(ItemStack original) {
        if (original == null)
            return new ShulkerFilterResult(null, 0);
        if (!(original.getItemMeta() instanceof BlockStateMeta))
            return new ShulkerFilterResult(original.clone(), 0);

        ItemStack cloned = original.clone();
        BlockStateMeta meta = (BlockStateMeta) cloned.getItemMeta();
        if (!(meta.getBlockState() instanceof ShulkerBox))
            return new ShulkerFilterResult(cloned, 0);

        ShulkerBox box = (ShulkerBox) meta.getBlockState();
        org.bukkit.inventory.Inventory inv = box.getInventory();
        ItemStack[] contents = inv.getContents();
        int removed = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack slot = contents[i];
            if (slot == null)
                continue;
            if (isItemTypeBlacklisted(slot.getType().toString().toLowerCase())) {
                contents[i] = null;
                removed++;
            }
        }
        inv.setContents(contents);
        meta.setBlockState(box);
        cloned.setItemMeta(meta);
        return new ShulkerFilterResult(cloned, removed);
    }

    // Vérifie si un ItemStack complet est blacklisté (utilisé pour bloquer
    // totalement un item tenu)
    private boolean isBlacklisted(ItemStack item) {
        if (blacklist.isEmpty())
            return false;
        String type = item.getType().toString().toLowerCase();
        return isItemTypeBlacklisted(type);
    }

    // Vérifie si un type d'item (string) correspond à la blacklist (supporte
    // shulker, head, skull, etc.)
    private boolean isItemTypeBlacklisted(String type) {
        if (blacklist.isEmpty())
            return false;
        for (String b : blacklist) {
            if (b.equals("shulker") && type.contains("shulker"))
                return true;
            if (b.equals("head") && type.contains("head"))
                return true;
            if (b.equals("skull") && type.contains("skull"))
                return true;
            if (type.equals(b))
                return true;
        }
        return false;
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