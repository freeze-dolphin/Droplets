@file:Suppress("DEPRECATION")

package io.sn.droplets.utils

import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils
import io.sn.droplets.DropletsCore
import io.sn.slimefun4.ChestMenuTexture
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack


object GuiUtils {

    private val UI_BACKGROUND: ItemStack = CustomItemStack(Material.GRAY_STAINED_GLASS_PANE, " ").setCustomModel(4000)
    private val ARROW_LEFT: ItemStack = CustomItemStack(Material.GRAY_STAINED_GLASS_PANE, " ").setCustomModel(4008)
    private val ARROW_RIGHT: ItemStack = CustomItemStack(Material.GRAY_STAINED_GLASS_PANE, " ").setCustomModel(4009)

    private fun amountIndicator(amt: Int, price: Double, type: ShopType): ItemStack {
        val value = amt * price
        val action = if (type == ShopType.SELL) "购买" else "出售"
        val color = if (type == ShopType.SELL) "yellow" else "green"
        return ItemStack(Material.NAME_TAG).apply {
            editMeta {
                it.displayName(DropletsCore.mini("<!italic><white>${action}个数: $amt <gray>($value)"))
                it.lore(mutableListOf(
                    Component.text(""),
                    DropletsCore.mini("<!italic><$color>左键 <white>增加 1 个"),
                    DropletsCore.mini("<!italic><$color>右键 <white>减少 1 个"),
                    DropletsCore.mini("<!italic><$color>Shift + 左键 <white>增加 8 个"),
                    DropletsCore.mini("<!italic><$color>Shift + 右键 <white>减少 8 个"),
                    Component.text(""),
                    DropletsCore.mini("<!italic><$color>单击物品 <white>确定${action}"),
                ).apply {
                    if (type == ShopType.BUY) {
                        add(DropletsCore.mini("<!italic><green>Shift + 单击物品 <white>一键出售所有该物品"))
                    }
                })
            }
        }
    }

    /**
     * @param page zero-indexed page number
     */
    private fun drawGui(plug: DropletsCore, id: String, page: Int): Quadruple<List<Pair<ItemStack, Double>>, PageState, String?, ShopType> {
        val stock = StorageUtils.getStock(plug, id)
        val items = stock.first
        val name = stock.second
        val type = stock.third

        val totalPage = if (items.size % 9 == 0) items.size / 9 else items.size / 9 + 1

        if (totalPage == 0) throw Exception("该货架没有商品")

        if (page !in 0..totalPage) throw Exception("不存在这一页")

        var state: PageState = PageState.NORMAL
        val endIdx: Int

        if (page == totalPage - 1) {
            endIdx = items.size
            state = PageState.LAST
        } else {
            endIdx = 9 * (page + 1)
        }

        if (page == 0) state = PageState.HOME

        if (totalPage == 1) state = PageState.ONLY_ONE

        return Quadruple(items.slice(9 * page until endIdx), state, name, type)
    }

    private fun restrictAmount(type: Material, tamount: Int): Int {
        if (tamount > type.maxStackSize) return type.maxStackSize
        if (tamount < 1) return 1
        return tamount
    }

    private fun getAmountByItemInInv(player: Player, itemStack: ItemStack): Int = player.inventory.sumOf {
        if (SlimefunUtils.isItemSimilar(it, itemStack, true)) {
            it.amount
        } else 0
    }

    private fun removeItemWithAmountFromInv(player: Player, item: ItemStack, amount: Int): Boolean {
        val targets = mutableListOf<Pair<Int, ItemStack>>()
        player.inventory.forEachIndexed { index, itm ->
            if (SlimefunUtils.isItemSimilar(itm, item, true)) {
                targets.add(Pair(index, itm.clone()))
            }
        }

        if (targets.size == 0) return false
        if (targets.sumOf { it.second.amount } < amount) return false

        var tamount = amount
        targets.forEach {
            val slot = it.first
            val itm = it.second

            val toRemove = tamount.coerceAtMost(itm.amount)
            player.inventory.getItem(slot)?.apply { this.amount -= toRemove }
            tamount -= toRemove
        }
        return true
    }

    /**
     * @param page zero-indexed page number
     */
    fun openGuiFor(plug: DropletsCore, plr: Player, id: String, page: Int) {
        val (content, state, name, type) = drawGui(plug, id, page)
        val desc = LegacyComponentSerializer.legacyAmpersand().serialize(DropletsCore.mini(name!!))
        val pageNum = if (state == PageState.ONLY_ONE) "" else "&7[&8${page + 1}&7]"
        val typeName = if (type == ShopType.SELL) "&6&l" else "&2&l"
        val inv = ChestMenu("$typeName\uD83D\uDED2 &8| $desc $pageNum", ChestMenuTexture("dumortierite", "droplets"))
        // <b><dark_green>🛒</b> | 测试内容 <dark_gray>[<gray>1</gray>]

        content.indices.forEach { i ->
            inv.addItem(i, content[i].first) { p, slot, item, action ->
                if (action.isRightClicked && action.isShiftClicked && p.hasPermission("droplets.edit.unstock")) {
                    plug.sendmsg(p, "<white>目标槽位为: <yellow>$id : ${page * 9 + slot}")
                    p.closeInventory()
                    return@addItem false
                }

                if (type == ShopType.BUY && action.isShiftClicked && !action.isRightClicked) {
                    val tamount = getAmountByItemInInv(plr, item)
                    val resp: EconomyResponse = DropletsCore.econ.depositPlayer(p, content[i].second * tamount)
                    val toBuy = item.clone().apply { amount = tamount }
                    if (!removeItemWithAmountFromInv(p, item, tamount)) {
                        plug.sendmsg(p, "<red>你没有这个物品")
                    } else if (resp.transactionSuccess()) {
                        p.inventory.remove(toBuy)
                        plug.sendmsg(p, "<green>成功出售: <white>${ItemNameUtils.getItemName(item)} <white>x$tamount")
                    } else {
                        plug.sendmsg(p, "<red>出售时出现错误")
                    }
                    return@addItem false
                }

                val tamount = inv.getItemInSlot(slot + 9).amount
                when (type) {
                    ShopType.SELL -> {
                        val resp: EconomyResponse = DropletsCore.econ.withdrawPlayer(p, content[i].second * tamount)
                        if (p.inventory.firstEmpty() == -1) {
                            plug.sendmsg(p, "<red>无法购买, 请检查背包空间")
                        } else if (resp.transactionSuccess()) {
                            p.inventory.addItem(item.clone().apply { amount = tamount })
                            plug.sendmsg(p, "<green>成功购买: <white>${ItemNameUtils.getItemName(item)} <white>x$tamount")
                        } else {
                            plug.sendmsg(p, "<red>无法购买, 请检查账户余额")
                        }
                    }

                    ShopType.BUY -> {
                        val resp: EconomyResponse = DropletsCore.econ.depositPlayer(p, content[i].second * tamount)
                        val toBuy = item.clone().apply { amount = tamount }
                        if (!removeItemWithAmountFromInv(p, item, tamount)) {
                            plug.sendmsg(p, "<red>你没有这些物品")
                        } else if (resp.transactionSuccess()) {
                            p.inventory.remove(toBuy)
                            plug.sendmsg(p, "<green>成功出售: <white>${ItemNameUtils.getItemName(item)} <white>x$tamount")
                        } else {
                            plug.sendmsg(p, "<red>出售时出现错误")
                        }
                    }
                }
                false
            }
            inv.addItem(i + 9, amountIndicator(1, content[i].second, type)) { _, _, item, action ->
                val amount = item.amount
                val tamount = restrictAmount(
                    inv.getItemInSlot(i).type, if (!action.isShiftClicked) {
                        if (!action.isRightClicked) {
                            amount + 1
                        } else {
                            amount - 1
                        }
                    } else {
                        if (!action.isRightClicked) {
                            amount + 8
                        } else {
                            amount - 8
                        }
                    }
                )
                item.itemMeta = amountIndicator(tamount, content[i].second, type).itemMeta
                item.amount = tamount
                false
            }
        }

        (content.size..8).forEach {
            inv.addItem(it, UI_BACKGROUND, ChestMenuUtils.getEmptyClickHandler())
            inv.addItem(it + 9, UI_BACKGROUND, ChestMenuUtils.getEmptyClickHandler())
        }

        (18..35).forEach {
            when (it) {
                28 -> {
                    inv.addItem(it,
                        if (state != PageState.ONLY_ONE && state != PageState.HOME) ARROW_LEFT else UI_BACKGROUND,
                        if (state != PageState.ONLY_ONE && state != PageState.HOME) {
                            ChestMenu.MenuClickHandler { _, _, _, _ ->
                                openGuiFor(plug, plr, id, page - 1)
                                return@MenuClickHandler false
                            }
                        } else {
                            ChestMenuUtils.getEmptyClickHandler()
                        })
                }

                34 -> {
                    inv.addItem(it,
                        if (state != PageState.ONLY_ONE && state != PageState.LAST) ARROW_RIGHT else UI_BACKGROUND,
                        if (state != PageState.ONLY_ONE && state != PageState.LAST) {
                            ChestMenu.MenuClickHandler { _, _, _, _ ->
                                openGuiFor(plug, plr, id, page + 1)
                                return@MenuClickHandler false
                            }
                        } else {
                            ChestMenuUtils.getEmptyClickHandler()
                        })
                }

                else -> {
                    inv.addItem(it, UI_BACKGROUND, ChestMenuUtils.getEmptyClickHandler())
                }
            }
        }

        inv.open(plr)
    }

}

enum class PageState {
    HOME, LAST, NORMAL, ONLY_ONE
}
