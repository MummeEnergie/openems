// @ts-strict-ignore
import { Component, OnInit } from "@angular/core";
import { AbstractModal } from "src/app/shared/components/modal/abstractModal";
import { ChannelAddress, CurrentData } from "src/app/shared/shared";

interface ControllerInfo {
  componentId: string;
  alias: string;
  factoryId: string;
  ceiling: number | null;
  floor: number | null;
  override: number | null;
}

@Component({
  templateUrl: "./modal.html",
  standalone: false,
})
export class ModalComponent extends AbstractModal implements OnInit {

  private static readonly EVCS_PRICING_ID = "_evcsPricing";
  private static readonly PRICING_FACTORIES = [
    "Controller.Evcs.FixedPricing",
    "Controller.Evcs.PvPricing",
    "Controller.Evcs.BatteryPricing",
  ];

  public currentPrice: number | null = null;
  public nextPrice: number | null = null;
  public nextPriceChange: number | null = null;
  public overrideSource: string | null = null;
  public overrideValue: number | null = null;
  public controllers: ControllerInfo[] = [];

  private pricingControllerIds: string[] = [];

  public formatNextChange(): string {
    if (this.nextPriceChange == null) {
      return "-";
    }
    return new Date(this.nextPriceChange).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  }

  public getConstraintType(ctrl: ControllerInfo): string {
    if (ctrl.override != null) {
      return "Override";
    }
    if (ctrl.ceiling != null) {
      return "Ceiling";
    }
    if (ctrl.floor != null) {
      return "Floor";
    }
    return "Inactive";
  }

  public getConstraintValue(ctrl: ControllerInfo): string {
    const value = ctrl.override ?? ctrl.ceiling ?? ctrl.floor;
    if (value == null) {
      return "-";
    }
    return value.toFixed(4) + " €/kWh";
  }

  protected override getChannelAddresses(): ChannelAddress[] {
    const addresses: ChannelAddress[] = [
      new ChannelAddress(ModalComponent.EVCS_PRICING_ID, "Price"),
      new ChannelAddress(ModalComponent.EVCS_PRICING_ID, "NextIntervalPrice"),
      new ChannelAddress(ModalComponent.EVCS_PRICING_ID, "NextPriceChange"),
      new ChannelAddress(ModalComponent.EVCS_PRICING_ID, "ActiveOverrideSource"),
      new ChannelAddress(ModalComponent.EVCS_PRICING_ID, "ActiveOverrideValue"),
    ];

    this.pricingControllerIds = [];
    this.controllers = [];

    if (this.config) {
      for (const factory of ModalComponent.PRICING_FACTORIES) {
        const componentIds = this.config.getComponentIdsByFactory(factory);
        for (const id of componentIds) {
          const comp = this.config.getComponent(id);
          if (comp?.isEnabled) {
            this.pricingControllerIds.push(id);
            this.controllers.push({
              componentId: id,
              alias: comp.alias || id,
              factoryId: factory,
              ceiling: null,
              floor: null,
              override: null,
            });
            addresses.push(new ChannelAddress(id, "ActiveCeiling"));
            addresses.push(new ChannelAddress(id, "ActiveFloor"));
            addresses.push(new ChannelAddress(id, "ActiveOverride"));
          }
        }
      }
    }

    return addresses;
  }

  protected override onCurrentData(currentData: CurrentData) {
    const prefix = ModalComponent.EVCS_PRICING_ID + "/";
    this.currentPrice = currentData.allComponents[prefix + "Price"];
    this.nextPrice = currentData.allComponents[prefix + "NextIntervalPrice"];
    this.nextPriceChange = currentData.allComponents[prefix + "NextPriceChange"];
    this.overrideSource = currentData.allComponents[prefix + "ActiveOverrideSource"];
    this.overrideValue = currentData.allComponents[prefix + "ActiveOverrideValue"];

    for (const ctrl of this.controllers) {
      ctrl.ceiling = currentData.allComponents[ctrl.componentId + "/ActiveCeiling"];
      ctrl.floor = currentData.allComponents[ctrl.componentId + "/ActiveFloor"];
      ctrl.override = currentData.allComponents[ctrl.componentId + "/ActiveOverride"];
    }
  }

}
