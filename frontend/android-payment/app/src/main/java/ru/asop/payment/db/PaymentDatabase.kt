package ru.asop.payment.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PendingPaymentEntity::class],
    version = 2,
    exportSchema = false
)
abstract class PaymentDatabase : RoomDatabase() {

    abstract fun pendingPaymentDao(): PendingPaymentDao

    companion object {
        @Volatile private var instance: PaymentDatabase? = null

        fun get(context: Context): PaymentDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PaymentDatabase::class.java,
                    "app_payment.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}