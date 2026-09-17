// @ts-strict-ignore
import { Component } from "@angular/core";
import { AbstractFlatWidget } from "src/app/shared/components/flat/abstract-flat-widget";
import { ChannelAddress, CurrentData } from "src/app/shared/shared";
import { ModalComponent } from "../modal/modal";

@Component({
  selector: "Core_EvcsPricing",
  templateUrl: "./flat.html",
  standalone: false,
})
export class FlatComponent extends AbstractFlatWidget {

  private static readonly EVCS_PRICING_ID = "_evcsPricing";

  public currentPrice: number | null = null;
  public nextPrice: number | null = null;
  public nextPriceChange: number | null = null;
  public overrideSource: string | null = null;

  async presentModal() {
    if (!this.isInitialized) {
      return;
    }
    const modal = await this.modalController.create({
      component: ModalComponent,
      componentProps: {
        component: this.component,
      },
    });
    return await modal.present();
  }

  public formatNextChange(): string {
    if (this.nextPriceChange == null) {
      return "-";
    }
    return new Date(this.nextPriceChange).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  }

  protected override getChannelAddresses(): ChannelAddress[] {
    return [
      new ChannelAddress(FlatComponent.EVCS_PRICING_ID, "Price"),
      new ChannelAddress(FlatComponent.EVCS_PRICING_ID, "NextIntervalPrice"),
      new ChannelAddress(FlatComponent.EVCS_PRICING_ID, "NextPriceChange"),
      new ChannelAddress(FlatComponent.EVCS_PRICING_ID, "ActiveOverrideSource"),
    ];
  }

  protected override onCurrentData(currentData: CurrentData) {
    const prefix = FlatComponent.EVCS_PRICING_ID + "/";
    this.currentPrice = currentData.allComponents[prefix + "Price"];
    this.nextPrice = currentData.allComponents[prefix + "NextIntervalPrice"];
    this.nextPriceChange = currentData.allComponents[prefix + "NextPriceChange"];
    this.overrideSource = currentData.allComponents[prefix + "ActiveOverrideSource"];
  }

}
