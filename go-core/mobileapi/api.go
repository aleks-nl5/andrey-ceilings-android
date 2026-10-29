// Package mobileapi exposes a gomobile-friendly JSON boundary for Android.
// Keep exported signatures limited to strings so generated Java bindings stay stable.
package mobileapi

import (
	"encoding/json"
	"fmt"

	"github.com/aleks-nl5/andrey-ceilings-native/go-core/core"
)

type estimateRequest struct {
	Geometry core.GeometryInput `json:"geometry"`
	Cost     core.CostInput     `json:"cost"`
	Prices   *core.PriceList    `json:"prices,omitempty"`
}

type estimateResponse struct {
	Geometry core.Geometry      `json:"geometry"`
	Cost     core.CostBreakdown `json:"cost"`
	Error    string             `json:"error,omitempty"`
}

// CalculateJSON calculates room geometry and ceiling costs from one JSON request.
// It returns JSON instead of Go structs to keep the Android binding API simple.
func CalculateJSON(input string) string {
	var req estimateRequest
	if err := json.Unmarshal([]byte(input), &req); err != nil {
		return errorJSON(fmt.Errorf("invalid request: %w", err))
	}
	prices := core.DefaultPriceList()
	if req.Prices != nil {
		prices = *req.Prices
	}
	geometry := core.CalculateRoomGeometry(req.Geometry)
	req.Cost.Area = geometry.Area
	req.Cost.Perimeter = geometry.Perimeter
	req.Cost.PrecisionStepMeters = req.Geometry.PrecisionStepMeters
	return marshal(estimateResponse{
		Geometry: geometry,
		Cost:     core.CalculateCeilingCost(req.Cost, prices),
	})
}

// DefaultPricesJSON returns the current default price list as JSON.
func DefaultPricesJSON() string {
	data, err := json.Marshal(core.DefaultPriceList())
	if err != nil {
		return errorJSON(err)
	}
	return string(data)
}

func marshal(v estimateResponse) string {
	data, err := json.Marshal(v)
	if err != nil {
		return errorJSON(err)
	}
	return string(data)
}

func errorJSON(err error) string {
	data, _ := json.Marshal(estimateResponse{Error: err.Error()})
	return string(data)
}
