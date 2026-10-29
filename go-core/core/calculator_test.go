package core

import "testing"

func TestDefaultPriceListAndRectangle(t *testing.T) {
	prices := DefaultPriceList()
	g := CalculateRoomGeometry(GeometryInput{Shape: Rectangle, Length: 5.4, Width: 3.8})
	if g.Area != 20.52 || g.Perimeter != 18.4 || g.CornersCount != 4 {
		t.Fatalf("unexpected geometry: %+v", g)
	}
	c := CalculateCeilingCost(CostInput{
		Area: g.Area, Perimeter: g.Perimeter, MaterialType: PVCMatte,
		Complexity: Straight, ProfileType: StandardAlu,
		Options: RoomOptions{ChandeliersCount: 1, SpotCount: 4, PipePassCount: 1},
	}, prices)
	if c.CanvasCost != 5951 || c.ProfileCost != 2392 || c.OptionsCost != 1410 {
		t.Fatalf("unexpected cost: %+v", c)
	}
	if c.TotalCost != 9753 {
		t.Fatalf("total=%v, want 9753", c.TotalCost)
	}
}
func TestProfileDoesNotDoubleChargeShadow(t *testing.T) {
	p := DefaultPriceList()
	got := CalculateCeilingCost(CostInput{
		Area: 10, Perimeter: 12, MaterialType: PVCMatte, Complexity: Straight,
		ProfileType: ShadowKraab, Options: RoomOptions{ShadowProfileMeters: 12},
	}, p)
	if got.OptionsCost != 0 {
		t.Fatalf("shadow profile should not be double charged: %+v", got)
	}
}
func TestNegativeValuesAreClamped(t *testing.T) {
	got := CalculateCeilingCost(CostInput{Area: -10, Perimeter: -3, MaterialType: PVCMatte, ProfileType: StandardAlu, Options: RoomOptions{SpotCount: -2}}, DefaultPriceList())
	if got.TotalCost != 0 {
		t.Fatalf("expected zero for negative inputs, got %+v", got)
	}
}
func TestLShapedGeometry(t *testing.T) {
	got := CalculateRoomGeometry(GeometryInput{Shape: LShaped, SideA: 5, SideB: 4, SideC: 2, SideD: 2, SideE: 3, SideF: 2})
	if got.Area != 14 || got.Perimeter != 18 || got.CornersCount != 6 {
		t.Fatalf("unexpected L geometry: %+v", got)
	}
}
