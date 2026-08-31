package com.example.plugin

data class Product(
    val imageUrl: String,
    val name: String,
    val price: String,
    val rating: String,
    val oldPrice: String,
)

val products = listOf(
    Product(
        imageUrl = "https://basket-05.wbbasket.ru/vol945/part94570/94570870/images/c516x688/1.webp",
        name = "Баночки для специй стеклянные 6 шт.",
        price = "988 ₽",
        rating = "★ 4.9",
        oldPrice = "1 123 ₽",
    ),
    Product(
        imageUrl = "https://basket-15.wbbasket.ru/vol2307/part230753/230753898/images/big/1.webp",
        name = "Разделочные доски",
        price = "877 ₽",
        rating = "★ 4.9",
        oldPrice = "",
    ),
    Product(
        imageUrl = "https://basket-17.wbbasket.ru/vol2775/part277539/277539042/images/big/1.webp",
        name = "Термокружка",
        price = "204 ₽",
        rating = "★ 4.0",
        oldPrice = "232 ₽",
    ),
    Product(
        imageUrl = "https://basket-12.wbbasket.ru/vol1690/part169076/169076421/images/big/1.webp",
        name = "Корзины для хранения",
        price = "1 957 ₽",
        rating = "★ 4.8",
        oldPrice = "2 224 ₽",
    ),
    Product(
        imageUrl = "https://basket-17.wbbasket.ru/vol2641/part264181/264181077/images/big/1.webp",
        name = "Экран под ванну 180 см МДФ",
        price = "7 802 ₽",
        rating = "★ 5.0",
        oldPrice = "8 866 ₽",
    ),
    Product(
        imageUrl = "https://basket-13.wbbasket.ru/vol2018/part201820/201820002/images/big/1.webp",
        name = "Разделители для ящиков комода и полок",
        price = "1 392 ₽",
        rating = "★ 4.7",
        oldPrice = "1 582 ₽",
    ),
    Product(
        imageUrl = "https://basket-13.wbbasket.ru/vol1964/part196426/196426420/images/big/1.webp",
        name = "Контейнеры для еды с крышкой стеклянные герметичные 4 шт.",
        price = "1 246 ₽",
        rating = "★ 4.4",
        oldPrice = "1 417 ₽",
    ),
    Product(
        imageUrl = "https://basket-20.wbbasket.ru/vol3362/part336281/336281413/images/big/1.webp",
        name = "Диспенсер для пищевой пленки и фольги с резаком",
        price = "1 211 ₽",
        rating = "★ 4.8",
        oldPrice = "1 377 ₽",
    ),
    Product(
        imageUrl = "https://basket-10.wbbasket.ru/vol1555/part155525/155525466/images/big/1.webp",
        name = "Органайзер для пищевой плёнки и фольги с резаком",
        price = "1 338 ₽",
        rating = "★ 4.8",
        oldPrice = "1 521 ₽",
    ),
    Product(
        imageUrl = "https://basket-15.wbbasket.ru/vol2345/part234567/234567838/images/big/1.webp",
        name = "Полка органайзер с выдвижными корзинами",
        price = "2 728 ₽",
        rating = "★ 4.5",
        oldPrice = "3 101 ₽",
    ),
)
