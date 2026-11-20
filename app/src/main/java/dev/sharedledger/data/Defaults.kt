package dev.sharedledger.data

import dev.sharedledger.domain.EntryType
import dev.sharedledger.domain.id

val categoryColors = listOf(0xFF427B62, 0xFFF1AE74, 0xFF9270AC, 0xFF5793AE, 0xFFD87D77, 0xFFD5AC56, 0xFF6986B5, 0xFF78A791)
val categoryIcons = listOf("basket", "dining", "coffee", "delivery", "home", "bill", "wifi", "phone", "train", "car", "fuel", "parking", "health", "pharmacy", "sports", "clothes", "beauty", "sofa", "devices", "film", "book", "travel", "gift", "pets", "child", "shield", "heart", "wallet", "work", "chart", "more")

private val expenses = listOf(
    "Groceries" to "basket", "Restaurants" to "dining", "Coffee" to "coffee", "Food Delivery" to "delivery",
    "Rent" to "home", "Mortgage" to "home", "Utilities" to "bill", "Internet" to "wifi",
    "Mobile Phone" to "phone", "Public Transport" to "train", "Taxi" to "car", "Fuel" to "fuel",
    "Car Maintenance" to "car", "Parking" to "parking", "Healthcare" to "health", "Pharmacy" to "pharmacy",
    "Fitness & Sports" to "sports", "Clothing" to "clothes", "Beauty & Personal Care" to "beauty",
    "Household" to "home", "Furniture" to "sofa", "Electronics" to "devices", "Entertainment" to "film",
    "Subscriptions" to "bill", "Education" to "book", "Travel" to "travel", "Gifts" to "gift",
    "Pets" to "pets", "Children" to "child", "Insurance" to "shield", "Taxes & Fees" to "bill",
    "Charity" to "heart", "Other" to "more"
)

private val incomes = listOf(
    "Salary" to "work", "Bonus" to "gift", "Freelance" to "devices", "Business Income" to "work",
    "Investments" to "chart", "Dividends & Interest" to "chart", "Gifts Received" to "gift",
    "Refunds" to "wallet", "Cashback" to "wallet", "Reimbursements" to "wallet",
    "Selling Items" to "basket", "Other Income" to "more"
)

fun startingCategories(budget: String) =
    listOf(EntryType.EXPENSE to expenses, EntryType.INCOME to incomes).flatMap { (type, defaults) ->
        defaults.mapIndexed { index, (name, icon) ->
            Category(id(), budget, name, type, icon, categoryColors[index % categoryColors.size], index)
        }
    }
