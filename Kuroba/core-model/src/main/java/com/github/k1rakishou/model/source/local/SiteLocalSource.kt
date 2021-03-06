package com.github.k1rakishou.model.source.local

import com.github.k1rakishou.model.KurobaDatabase
import com.github.k1rakishou.model.data.descriptor.SiteDescriptor
import com.github.k1rakishou.model.data.site.ChanSiteData
import com.github.k1rakishou.model.mapper.ChanSiteMapper
import com.github.k1rakishou.model.source.cache.ChanDescriptorCache

class SiteLocalSource(
  database: KurobaDatabase,
  private val isDevFlavor: Boolean,
  private val chanDescriptorCache: ChanDescriptorCache
) : AbstractLocalSource(database) {
  private val TAG = "SiteLocalSource"
  private val chanSiteDao = database.chanSiteDao()

  suspend fun createDefaults(allSiteDescriptors: Collection<SiteDescriptor>) {
    ensureInTransaction()

    chanSiteDao.createDefaultsIfNecessary(allSiteDescriptors)
  }

  suspend fun selectAllOrderedDesc(): List<ChanSiteData> {
    ensureInTransaction()

    return chanSiteDao.selectAllOrderedDescWithSettings()
      .map { chanSiteEntity -> ChanSiteMapper.fromChanSiteEntity(chanSiteEntity) }
  }

  suspend fun persist(chanSiteDataList: Collection<ChanSiteData>) {
    ensureInTransaction()

    val entities = chanSiteDataList.mapIndexed { index, chanSiteData ->
      ChanSiteMapper.toChanSiteEntity(index, chanSiteData)
    }

    chanSiteDao.updateManySites(entities)
  }

}