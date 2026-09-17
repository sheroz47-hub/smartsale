package uz.smartsale.agent.data

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Движок акций: считает скидки при наборе заказа прямо на телефоне.
 *
 * Ровно то, чего не хватало в Моби-С: акции применяются на клиенте, в момент
 * набора, а не где-то на сервере после. Условия акций настраиваются в УТ и
 * приезжают с сервера (PromotionEntity), а здесь — только расчёт. Результат
 * уходит в заказ: процент скидки в строке (в УТ — ручная скидка) и бонусные
 * строки (в УТ — строка со 100% скидкой).
 *
 * Движок — чистая функция без обращений к базе и сети: его легко проверить
 * тестами и он одинаково считает при каждом изменении корзины.
 *
 * Механики:
 *  - percent: процент на товары/группы акции;
 *  - volume:  ступенчатая скидка от объёма (пороги по количеству или сумме
 *             товаров акции в заказе); берётся наибольший подходящий процент;
 *  - bonus:   купи N единиц товаров акции — получи M бонусного бесплатно.
 *
 * На одну строку действует одна скидочная акция (percent/volume) — с
 * наибольшим приоритетом, при равенстве — с большим процентом. Бонусы
 * начисляются независимо и в дополнение к скидкам.
 *
 * Сегменты клиентов пока не сопоставляются на телефоне (в выгрузке клиентов
 * сегмента ещё нет): акции с заданным сегментом пропускаются, кроме случая,
 * когда сегмент клиента передан и совпал. Акция без сегмента — всем.
 */
object PromotionEngine {

    private val HUNDRED = BigDecimal(100)

    data class Line(
        val productUuid: String,
        val categoryUuid: String?,
        val price: BigDecimal,
        val qty: BigDecimal,
    )

    data class Threshold(
        val minQty: BigDecimal,
        val minSum: BigDecimal,
        val percent: BigDecimal,
    )

    data class Promotion(
        val uuid: String,
        val mechanic: String,
        val dateFrom: String,
        val dateTo: String,
        val segmentUuid: String,
        val priority: Int,
        val percent: BigDecimal,
        val buyQty: BigDecimal,
        val bonusProductUuid: String,
        val bonusQty: BigDecimal,
        val productUuids: Set<String>,
        val groupUuids: Set<String>,
        val thresholds: List<Threshold>,
    ) {
        /** Строка входит в область действия акции: товар прямо в списке акции
         *  либо его группа. */
        fun matches(line: Line): Boolean =
            line.productUuid in productUuids ||
                (line.categoryUuid != null && line.categoryUuid in groupUuids)
    }

    data class LineDiscount(
        val discountPercent: BigDecimal,
        val promotionUuid: String,
        val priority: Int,
    )

    data class Bonus(
        val productUuid: String,
        val qty: BigDecimal,
        val promotionUuid: String,
    )

    /** Скидки по строкам (ключ — productUuid) и бонусные строки. */
    data class Result(
        val lineDiscounts: Map<String, LineDiscount>,
        val bonuses: List<Bonus>,
    )

    /**
     * @param today дата в формате «ГГГГ-ММ-ДД» (сравнение строк — ISO-даты
     *        упорядочены лексикографически).
     * @param customerSegmentUuid сегмент клиента, если известен (пока null).
     */
    fun apply(
        lines: List<Line>,
        promotions: List<Promotion>,
        today: String,
        customerSegmentUuid: String? = null,
    ): Result {
        val действующие = promotions.filter { действует(it, today, customerSegmentUuid) }

        val скидки = mutableMapOf<String, LineDiscount>()
        val бонусы = mutableListOf<Bonus>()

        for (акция in действующие) {
            when (акция.mechanic) {
                "percent" -> {
                    val процент = ограничить(акция.percent)
                    // Нулевой процент строку не занимает — иначе пустая акция с
                    // высоким приоритетом вытеснила бы осмысленную скидку.
                    // Если задано КупитьКоличество (>0) — это минимальный порог:
                    // процент включается, только когда набрано столько товаров
                    // акции суммарно (а не на любое количество).
                    val набрано = lines.filter { акция.matches(it) }
                        .fold(BigDecimal.ZERO) { s, л -> s + л.qty }
                    val хватает = акция.buyQty.signum() <= 0 || набрано >= акция.buyQty
                    if (процент.signum() > 0 && хватает) {
                        for (строка in lines) {
                            if (акция.matches(строка)) {
                                применить(скидки, строка.productUuid, процент, акция)
                            }
                        }
                    }
                }
                "volume" -> {
                    val подходящие = lines.filter { акция.matches(it) }
                    if (подходящие.isEmpty()) continue
                    val объёмКол = подходящие.fold(BigDecimal.ZERO) { s, л -> s + л.qty }
                    val объёмСум = подходящие.fold(BigDecimal.ZERO) { s, л -> s + л.qty * л.price }
                    val процент = лучшийПорог(акция.thresholds, объёмКол, объёмСум)
                    if (процент.signum() > 0) {
                        val огр = ограничить(процент)
                        for (строка in подходящие) {
                            применить(скидки, строка.productUuid, огр, акция)
                        }
                    }
                }
                "bonus" -> {
                    if (акция.buyQty.signum() <= 0 || акция.bonusQty.signum() <= 0) continue
                    if (акция.bonusProductUuid.isEmpty()) continue
                    val куплено = lines.filter { акция.matches(it) }
                        .fold(BigDecimal.ZERO) { s, л -> s + л.qty }
                    // Сколько полных наборов «N купленных» набралось.
                    val наборов = куплено.divideToIntegralValue(акция.buyQty)
                    val бонусКол = наборов * акция.bonusQty
                    if (бонусКол.signum() > 0) {
                        бонусы.add(Bonus(акция.bonusProductUuid, бонусКол, акция.uuid))
                    }
                }
            }
        }

        return Result(скидки, объединитьБонусы(бонусы))
    }

    private fun действует(
        акция: Promotion, today: String, customerSegmentUuid: String?,
    ): Boolean {
        if (акция.dateFrom.isNotEmpty() && акция.dateFrom > today) return false
        if (акция.dateTo.isNotEmpty() && акция.dateTo < today) return false
        // Сегмент задан → нужен клиент этого сегмента. Сегмент клиента пока не
        // приходит, поэтому сегментные акции по умолчанию не применяются.
        if (акция.segmentUuid.isNotEmpty() && акция.segmentUuid != customerSegmentUuid) {
            return false
        }
        return true
    }

    /** Назначаем строке скидку акции, если она главнее уже назначенной:
     *  сначала по приоритету, при равном приоритете — по проценту. Так на
     *  строке остаётся одна, самая значимая скидочная акция. */
    private fun применить(
        скидки: MutableMap<String, LineDiscount>,
        productUuid: String,
        процент: BigDecimal,
        акция: Promotion,
    ) {
        val текущая = скидки[productUuid]
        val главнее = when {
            текущая == null -> true
            акция.priority != текущая.priority -> акция.priority > текущая.priority
            процент.compareTo(текущая.discountPercent) != 0 ->
                процент > текущая.discountPercent
            // Полное равенство приоритета и процента: выбираем меньший uuid —
            // чтобы приписанная строке акция не зависела от порядка обхода.
            else -> акция.uuid < текущая.promotionUuid
        }
        if (главнее) {
            скидки[productUuid] = LineDiscount(процент, акция.uuid, акция.priority)
        }
    }

    /** Наибольший процент среди порогов, чей минимум по количеству ИЛИ сумме
     *  достигнут. Порог с нулевыми минимумами игнорируется. */
    private fun лучшийПорог(
        thresholds: List<Threshold>, объёмКол: BigDecimal, объёмСум: BigDecimal,
    ): BigDecimal {
        var лучший = BigDecimal.ZERO
        for (порог in thresholds) {
            val поКоличеству = порог.minQty.signum() > 0 && объёмКол >= порог.minQty
            val поСумме = порог.minSum.signum() > 0 && объёмСум >= порог.minSum
            if ((поКоличеству || поСумме) && порог.percent > лучший) {
                лучший = порог.percent
            }
        }
        return лучший
    }

    private fun объединитьБонусы(бонусы: List<Bonus>): List<Bonus> {
        if (бонусы.size <= 1) return бонусы
        // Один бонусный товар от разных акций — суммируем количество.
        return бонусы.groupBy { it.productUuid }.map { (товар, список) ->
            Bonus(товар, список.fold(BigDecimal.ZERO) { s, б -> s + б.qty }, список.first().promotionUuid)
        }
    }

    private fun ограничить(процент: BigDecimal): BigDecimal =
        процент.max(BigDecimal.ZERO).min(HUNDRED).setScale(2, RoundingMode.HALF_UP)
}
