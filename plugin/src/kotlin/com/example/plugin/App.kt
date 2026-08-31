package com.example.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.kernel.CaptionText
import com.example.kernel.Card
import com.example.kernel.HorizontalScrollRow
import com.example.kernel.Image
import com.example.kernel.OldPriceText
import com.example.kernel.PriceText
import com.example.kernel.Row
import com.example.kernel.SearchBar
import com.example.kernel.SectionTitle
import com.example.kernel.TitleText
import com.example.kernel.VerticalScrollColumn
import com.example.kernel.runRdmaApp

@Composable
fun ProductCard(imageUrl: String, name: String, price: String, rating: String, oldPrice: String) {
    Card {
        Image(imageUrl)
        TitleText(name)
        PriceText(price)
        if (oldPrice.isNotEmpty()) {
            OldPriceText(oldPrice)
        }
        CaptionText(rating)
    }
}

@Composable
fun ShopPage() {
    var query by remember { mutableStateOf("") }

    VerticalScrollColumn {
        SearchBar(
            query,
            onValueChange = { query = it; println("search: $it") },
            onClear = { println("clear: '$query'"); query = "" },
        )

        SectionTitle("Рекомендуем")
        HorizontalScrollRow {
            for (p in products) {
                ProductCard(p.imageUrl, p.name, p.price, p.rating, p.oldPrice)
            }
        }

        SectionTitle("Все товары")
        for (i in products.indices step 2) {
            Row {
                ProductCard(products[i].imageUrl, products[i].name, products[i].price, products[i].rating, products[i].oldPrice)
                if (i + 1 < products.size) {
                    ProductCard(products[i + 1].imageUrl, products[i + 1].name, products[i + 1].price, products[i + 1].rating, products[i + 1].oldPrice)
                }
            }
        }
    }
}

fun main() {
    runPersonDemo()
    runRdmaApp { ShopPage() }
}
