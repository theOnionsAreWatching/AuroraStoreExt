package com.aurora.store.module

import android.content.Context
import com.aurora.gplayapi.data.serializers.LocaleSerializer
import com.aurora.gplayapi.data.serializers.PropertiesSerializer
import com.aurora.store.data.providers.WhitelistFilter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CommonModule {

    @Singleton
    @Provides
    fun providesJsonInstance(): Json {
        val module = SerializersModule {
            contextual(LocaleSerializer)
            contextual(PropertiesSerializer)
        }

        return Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            coerceInputValues = true
            serializersModule = module
        }
    }

    @Singleton
    @Provides
    fun providesWhitelistFilter(@ApplicationContext context: Context): WhitelistFilter {
        return WhitelistFilter(context)
    }
}
