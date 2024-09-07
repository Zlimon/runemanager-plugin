package com.zlimon.marketplace.notifications;

import com.zlimon.marketplace.MarketplaceEffect;
import com.zlimon.marketplace.products.EbsNotification;
import com.zlimon.marketplace.products.MarketplaceProduct;

public class Notification {
	public final MarketplaceProduct marketplaceProduct;
	public final MarketplaceEffect marketplaceEffect;
	public final EbsNotification ebsNotification;

	public Notification(MarketplaceProduct marketplaceProduct, MarketplaceEffect marketplaceEffect, EbsNotification ebsNotification)
	{
		this.marketplaceProduct = marketplaceProduct;
		this.marketplaceEffect = marketplaceEffect;
		this.ebsNotification = ebsNotification;
	}

	public boolean isDonationMessage()
	{
		return ebsNotification.message == null || ebsNotification.message.isEmpty();
	}
}
