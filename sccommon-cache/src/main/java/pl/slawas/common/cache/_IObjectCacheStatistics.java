package pl.slawas.common.cache;

public interface _IObjectCacheStatistics {

	/**
	 * Clears the statistic counters to 0 for the associated Cache.
	 */

	void clearStatistics();

	/**
	 * The number of times a requested item was found in the cache.
	 * 
	 * @return the number of times a requested item was found in the cache
	 */
	long getCacheHits();

	/**
	 * Number of times a requested item was found in the Memory Store.
	 * 
	 * @return the number of times a requested item was found in memory
	 */
	long getInMemoryHits();

	/**
	 * Number of times a requested item was found in the Disk Store.
	 * 
	 * @return the number of times a requested item was found on Disk, or 0 if
	 *         there is no disk storage configured.
	 */
	long getOnDiskHits();

	/**
	 * @return the number of times a requested element was not found in the
	 *         cache
	 */
	long getCacheMisses();

	/**
	 * Gets the number of elements stored in the cache.
	 * 
	 * @return the number of elements in the ehcache, with a varying degree of
	 *         accuracy, depending on accuracy setting.
	 */
	long getObjectCount();

	/**
	 * @return the name of the cache, or null is there no associated cache
	 */
	String getAssociatedCacheName();

	/**
	 * Zwraca przeliczoną informację o wydajności cache wyrażoną w procentach
	 * [%]
	 * 
	 * @return informacja o wydajności cache wyrażona w procentach [%]
	 */
	double getHitsRatio();
	
	int getSize();

}