package com.zlimon.ui;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

import com.zlimon.RuneManagerConfig;
import com.zlimon.RuneManagerPlugin;
import com.zlimon.fights.FightStateManager;
import com.zlimon.marketplace.MarketplaceManager;
import com.zlimon.twitch.TwitchApi;
import com.zlimon.twitch.TwitchState;
import com.zlimon.twitch.eventsub.TwitchEventSubClient;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

public class RuneManagerPanel extends PluginPanel
{
	private final JPanel mainPanel = new JPanel(new GridBagLayout());
	private final MaterialTabGroup tabGroup = new MaterialTabGroup(mainPanel);
	private final MaterialTab connectivityTab;
	private final MaterialTab combatTab;
	private final MaterialTab marketplaceTab;

	private final ConnectivityPanel connectivityPanel;
	private final CombatPanel combatPanel;
	private final MarketplacePanel marketplacePanel;

	public RuneManagerPanel(RuneManagerPlugin plugin, TwitchApi twitchApi, TwitchEventSubClient twitchEventSubClient, TwitchState twitchState, FightStateManager fightStateManager, MarketplaceManager marketplaceManager, CanvasListener canvasListener, RuneManagerConfig config)
	{
		super(true);
		setLayout(new BorderLayout());

		combatPanel = new CombatPanel(fightStateManager);
		connectivityPanel = new ConnectivityPanel(plugin, twitchApi, twitchEventSubClient, twitchState, canvasListener, config);
		marketplacePanel = new MarketplacePanel(marketplaceManager);

		connectivityTab = new MaterialTab("Status", tabGroup, connectivityPanel);
		marketplaceTab = new MaterialTab("Events", tabGroup, marketplacePanel);
		combatTab = new MaterialTab("Combat", tabGroup, combatPanel);

		tabGroup.setBorder(new EmptyBorder(5, 0, 0, 0));
		tabGroup.addTab(connectivityTab);
		tabGroup.addTab(marketplaceTab);
		tabGroup.addTab(combatTab);

		tabGroup.select(connectivityTab);

		add(tabGroup, BorderLayout.NORTH);
		add(mainPanel, BorderLayout.CENTER);
	}

	public void onGameTick()
	{
		marketplacePanel.onGameTick();
	}

	public void rebuild()
	{
		connectivityPanel.rebuild();
		combatPanel.rebuild();
		marketplacePanel.rebuild();
		repaint();
		revalidate();
	}

	public CombatPanel getCombatPanel()
	{
		return combatPanel;
	}

	public ConnectivityPanel getConnectivityPanel()
	{
		return connectivityPanel;
	}

	public MarketplacePanel getMarketplacePanel()
	{
		return marketplacePanel;
	}

	public static void initializePanelButton(JPanel panel, JLabel label, String buttonTitle, ButtonCallback buttonCallback)
	{
		panel.setLayout(new BorderLayout());
		panel.setBorder(new EmptyBorder(10, 10, 10, 10));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.add(label, BorderLayout.CENTER);
		Styles.styleBigLabel(label, buttonTitle);
		panel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent mouseEvent)
			{
				buttonCallback.execute();
			}

			@Override
			public void mouseEntered(MouseEvent mouseEvent)
			{
				panel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				panel.setCursor(new Cursor(Cursor.HAND_CURSOR));
			}

			@Override
			public void mouseExited(MouseEvent mouseEvent)
			{
				panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				panel.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
			}
		});
	}

	public interface ButtonCallback {
		public void execute();
	}
}
