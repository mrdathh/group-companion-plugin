package com.osrstracker.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** protocol.ts ContainerItem. */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ContainerItem extends Item
{
	/** Index of the slot inside the container. */
	private int slot;
	/** Only for the equipment container; null (omitted) otherwise. */
	private String equipSlot;
	/** "dose" when qty counts doses (potion storage); null (omitted) otherwise. */
	private String unit;

	public ContainerItem(Item item, int slot, String equipSlot)
	{
		this(item, slot, equipSlot, null);
	}

	public ContainerItem(Item item, int slot, String equipSlot, String unit)
	{
		super(item.getId(), item.getQty(), item.getName(), item.getGe(), item.getHa());
		this.slot = slot;
		this.equipSlot = equipSlot;
		this.unit = unit;
	}
}
